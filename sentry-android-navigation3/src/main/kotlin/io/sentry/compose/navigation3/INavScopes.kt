package io.sentry.compose.navigation3

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.Event.ON_DESTROY
import androidx.lifecycle.Lifecycle.Event.ON_PAUSE
import androidx.lifecycle.Lifecycle.Event.ON_RESUME
import androidx.lifecycle.Lifecycle.Event.ON_START
import androidx.lifecycle.Lifecycle.Event.ON_STOP
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.IScope
import io.sentry.IScopes
import io.sentry.ITransaction
import io.sentry.NoOpTransaction
import io.sentry.PropagationContext
import io.sentry.Scope
import io.sentry.ScopeCallback
import io.sentry.ScopeType
import io.sentry.SentryOptions
import io.sentry.SpanStatus
import io.sentry.TransactionContext
import io.sentry.TransactionOptions

/**
 * An [IScopes] implementation that lets us coordinate updates to navigation state across multiple
 * [SentryNavEffect]s. (Also supports updates managed by a single `SentryNavEffect`.)
 *
 * Needed because all `SentryNavEffect`s mutate a single [IScope] instance by default:
 * ```
 * SentryNavEffect
 *       ↓ owns
 *   NavSession
 *    ├── BackStackObserver — computes nav data and nav transactions
 *    └── INavScopes        — controls whether those results reach the real scope
 *               ↓
 *        NavLeaseCoordinator — arbitrates ownership across all effects
 *               ↓
 *          shared IScope
 * ```
 *
 * **Limitations**
 *
 * Multi-effect support is limited to scenarios where:
 *
 * - each `SentryNavEffect` is managed by a single [LifecycleOwner] (e.g., a multi-Activity app with
 *   one `SentryNavEffect` per Activity);
 *
 * - normally only one `SentryNavEffect` is active at a time; but
 *
 * - brief windows exist where multiple `SentryNavEffect`s may be simultaneously active (e.g., as
 *   the host app switches between activities).
 *
 * This class does ***not*** support scenarios where multiple `SentryNavEffect`s are meant to be
 * simultaneously active, whether nested or as siblings (e.g.,
 * [multi-resume mode](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode)
 * across two app activities).
 *
 * **A fundamental tradeoff**
 *
 * During `LifecycleOwner` overlap, the current transaction and scope can reflect **_either_**:
 *
 * 1. the outgoing owner that's still visible (in `STARTED` state after pausing), or
 * 2. the incoming owner that may crash before it becomes visible (in `CREATED` state)
 *
 * but not both. We've opted for (2) so that incoming destination populates crash context and work
 * performed is associated with it†, as start-up logic is more likely to be the source of a crash or
 * interesting spans than the outgoing destination's steady state. (If the incoming owner aborts
 * prematurely, the nav transaction and data for the most recently resumed owner still in at least
 * `STARTED` state is restored.)
 *
 * † Associating work with the incoming transaction means spans produced by the outgoing owner may
 * appear as if they were produced by the incoming owner (or vice versa, were our policy reversed).
 * A possible solution would be to implement a parent span delivery system similar to
 * [LocalSentrySpan], but that's a task for another day.
 *
 * **Only leaseholders generate nav transactions and nav data**
 *
 * This class works by only allowing `INavScopes` instances holding a lease to mutate the underlying
 * `IScope`.
 *
 * `INavScopes` instances without a lease write to a [stagedNavData] holder, which lets them
 * preserve their latest updates without overwriting another instance's published data. When an
 * instance acquires the lease, it publishes its staged data to the underlying `IScope`.
 *
 * **Lease handoff policy**
 *
 * The lease is acquired preemptively by an incoming `LifecycleOwner` when it applies its first
 * [back stack update][runBackStackUpdate].
 *
 * The displaced owner (if any) remains eligible for restoration until it stops. Pausing releases
 * the lease but preserves eligibility. If the incoming owner aborts before the displaced owner
 * stops, the lease reverts to the displaced owner and its nav data and unfinished transaction are
 * restored.
 *
 * The following diagram illustrates how handoff works:
 * ```
 *  Time
 *    │
 *    ▼
 *
 * SentryNavEffect A              Shared state                SentryNavEffect B
 * ─────────────────             ────────────                 ─────────────────
 *
 * attach()                      Navigation lease: none
 *   │
 * Initial back stack update
 *   │
 *   ├── acquire lease ────────► Navigation lease: A
 *   ├── create & bind txA ────► Scope transaction: txA
 *   ├── publish nav data ─────► Published data: A
 *   │
 * ON_RESUME                    A is eligible for restoration
 *   │
 * ON_PAUSE
 *   │
 *   ├────────────────────────► Navigation lease: none
 *   │                          txA and published data remain
 *   │                          A remains eligible for restoration
 *   │
 *   │                                                                attach()
 *   │                                                                   │
 *   │                          Navigation lease: none ◄─────────────────┤
 *   │                          Scope transaction: txA                   │
 *   │                          Published data: A                        │
 *   │                          B registered, awaiting initial back      │
 *   │                            stack update                           │
 *   │                                                                   │
 *   │                                                   Initial back stack update
 *   │                                                                   │
 *   │                                         suspend & unbind txA ─────┤
 *   │                          Navigation lease: B ◄── acquire lease ───┤
 *   │                          Scope transaction: txB ◄── bind txB ─────┤
 *   │                          Published data: B ◄── publish nav data ──┤
 *   │                          (rollback point)                         │
 *   │                                                                   │
 *   │                                                              ON_RESUME
 *   │                                                                   │
 *   │                          B eligible for restoration ◄─────────────┤
 *   │                                                                   │
 * ON_STOP                                                               │
 *   │                                                                   │
 *   ├── retain txA; timers continue                                     │
 *   ├────────────────────────► A no longer eligible for restoration     │
 *   │                                                                   │
 * ON_DESTROY                                                            │
 *   │                                                                   │
 *   ├── finish retained txA if unfinished                               │
 *   └────────────────────────► A no longer tracked                      │
 *                                                                   ON_PAUSE
 *                                                                       │
 *                              Navigation lease: none ◄─────────────────┤
 *                              Published data: B remains                │
 *                              Existing txB may finish naturally        │
 *                              No new transactions or publication by B  │
 *                                                                       │
 *                                                                   ON_STOP
 *                                                                       │
 *                              B no longer eligible for restoration ◄───┤
 *                                                                       │
 *                                                                 ON_DESTROY
 *                                                                       │
 *                              Finish & clear txB if still bound ◄──────┤
 *                              Clear B's remaining screen/back stack    │
 *                              Retain recorded breadcrumbs              │
 *                                                                       ▼
 *
 * Alternative at rollback point: B disposes before A stops
 *                              Finish & unbind txB ◄────── dispose B ───┤
 *                              Navigation lease: A                      │
 *                              Restore txA if unfinished and scope free │
 *                              Republish A's staged data                │
 * ```
 *
 * **Encapsulation policy**
 *
 * This class and [NavLeaseCoordinator] are meant to fully encapsulate knowledge about the
 * possibility of multiple `SentryNavEffect`s and the shared mutable state issues it raises.
 * Addressing those concerns is complicated – don't let that complexity escape into the rest of the
 * `SentryNavEffect` system!
 *
 * **Usage**
 *
 * 1. Create an `INavScopes` instance per `SentryNavEffect`.
 *
 * 2. Pass the same [NavLeaseCoordinator] instance to all `INavScopes` whose effects must be
 *    coordinated (i.e., that target the same `IScope`).
 *
 * 3. Call [attach] with the lifecycle that manages the effect before the first back stack update.
 *
 * 4. Route back stack updates through [runBackStackUpdate].
 *
 * 5. Call [dispose] when the effect leaves the composition.
 *
 * **Thread safety**
 *
 * This class is ***not*** thread-safe. Lifecycle callbacks and method invocations should be
 * confined to the same thread.
 */
internal class INavScopes(
  private val delegate: IScopes,
  private val coordinator: NavLeaseCoordinator = defaultNavLeaseCoordinator,
) : IScopes by delegate, LifecycleEventObserver {

  private val owner: Any = Any()
  private var attachedLifecycle: Lifecycle? = null
  private val stagedNavData = StagedNavData()

  /**
   * Whether [runBackStackUpdate] is processing a back-stack update.
   *
   * This flag distinguishes a [back stack update][runBackStackUpdate] from other requests that also
   * invoke [configureScope]. Needed in order to transfer lease ownership for the former but not the
   * latter.
   */
  private var isProcessingBackStackUpdate: Boolean = false

  /**
   * Registers this [INavScopes] instance with [lifecycle]. No-ops if `lifecycle` has already been
   * attached or is [destroyed][Lifecycle.Event.ON_DESTROY].
   *
   * Throws if a caller tries to attach this instance to more than one lifecycle.
   */
  fun attach(lifecycle: Lifecycle) {
    check(attachedLifecycle == null || attachedLifecycle === lifecycle) {
      "INavScopes has already been attached to a different lifecycle!"
    }

    if (attachedLifecycle === lifecycle) {
      return
    }
    if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
      return
    }

    attachedLifecycle = lifecycle

    coordinator.registerCandidate(
      owner = owner,
      isResumed = lifecycle.currentState == Lifecycle.State.RESUMED,
      isStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
      clearPublishedData = stagedNavData::clearPublishedFrom,
      publish = ::publishStagedNavData,
    )

    lifecycle.addObserver(this)
  }

  override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
    when (event) {
      ON_START -> coordinator.startCandidate(owner)
      ON_RESUME -> delegate.configureScope { coordinator.resumeCandidate(owner, it)?.invoke() }
      ON_PAUSE -> delegate.configureScope { coordinator.pauseCandidate(owner, it)?.invoke() }
      ON_STOP -> delegate.configureScope { coordinator.stopCandidate(owner, it)?.invoke() }
      ON_DESTROY -> dispose()
      else -> Unit
    }
  }

  /**
   * Runs [block] as a back stack update eligible to acquire the navigation lease.
   *
   * The observer prepares its update before invoking [configureScope], which acquires the lease
   * immediately before application. Preparation failures leave the previous owner intact.
   */
  fun runBackStackUpdate(block: () -> Unit) {
    isProcessingBackStackUpdate = true
    try {
      block()
    } finally {
      isProcessingBackStackUpdate = false
    }
  }

  override fun configureScope(scopeType: ScopeType?, callback: ScopeCallback) {
    val leasedCallback = ScopeCallback { scope ->
      if (isProcessingBackStackUpdate) {
        coordinator.activateCandidate(owner, scope)?.invoke()
      }
      callback.run(LeasedNavigationScope(scope))
    }

    if (scopeType == null) {
      delegate.configureScope(leasedCallback)
    } else {
      delegate.configureScope(scopeType, leasedCallback)
    }
  }

  override fun startTransaction(
    transactionContext: TransactionContext,
    transactionOptions: TransactionOptions,
  ): ITransaction {
    if (!coordinator.hasLease(owner)) {
      return NoOpTransaction.getInstance()
    }

    return delegate.startTransaction(transactionContext, transactionOptions).also { transaction ->
      if (!transaction.isNoOp) {
        coordinator.bindTransaction(owner, transaction)
      }
    }
  }

  override fun addBreadcrumb(breadcrumb: Breadcrumb, hint: Hint?) {
    stagedNavData.addBreadcrumb(coordinator.hasLease(owner)) {
      delegate.addBreadcrumb(breadcrumb, hint)
    }
  }

  override fun addBreadcrumb(breadcrumb: Breadcrumb) {
    stagedNavData.addBreadcrumb(coordinator.hasLease(owner)) {
      delegate.addBreadcrumb(breadcrumb)
    }
  }

  /**
   * Releases the lease, clears remaining published state, and finishes this instance's transaction.
   */
  fun dispose() {
    attachedLifecycle?.removeObserver(this)
    attachedLifecycle = null

    delegate.configureScope { coordinator.unregisterCandidate(owner, it)?.invoke() }
  }

  private fun publishStagedNavData() {
    delegate.configureScope { scope ->
      if (!coordinator.hasLease(owner)) {
        return@configureScope
      }
      stagedNavData.publishTo(scope)
    }

    stagedNavData.publishPendingBreadcrumb(coordinator.hasLease(owner))
  }

  private inner class LeasedNavigationScope(private val realScope: IScope) : IScope by realScope {

    override fun getScreen(): String? =
      stagedNavData.getScreen(realScope, coordinator.hasLease(owner))

    override fun setScreen(screen: String?) {
      stagedNavData.setScreen(screen, realScope, coordinator.hasLease(owner))
    }

    override fun setContexts(key: String?, value: Any?) {
      if (key != NAVIGATION_CONTEXT_KEY) {
        realScope.setContexts(key, value)
        return
      }

      stagedNavData.setNavigationContext(value, realScope, coordinator.hasLease(owner))
    }

    override fun getPropagationContext(): PropagationContext =
      if (coordinator.hasLease(owner)) {
        realScope.propagationContext
      } else {
        stagedNavData.propagationContext
      }

    override fun setPropagationContext(propagationContext: PropagationContext) {
      stagedNavData.propagationContext = propagationContext
      if (coordinator.hasLease(owner)) {
        realScope.propagationContext = propagationContext
      }
    }

    override fun withPropagationContext(
      callback: Scope.IWithPropagationContext
    ): PropagationContext =
      if (coordinator.hasLease(owner)) {
        realScope.withPropagationContext(callback).also { stagedNavData.propagationContext = it }
      } else {
        stagedNavData.withPropagationContext(callback)
      }
  }
}

/** Holds navigation data staged for publication. */
private class StagedNavData {

  private val scope = Scope(SentryOptions.empty())
  private var hasPropagationContextUpdate = false

  // These may differ from the staged values after background updates. Keep the exact published
  // references so cleanup preserves host replacements, including structurally equal replacements.
  private var publishedScreen: String? = null
  private var publishedNavigationContext: Any? = null

  /**
   * Whether this instance has received a screen update, including an explicit clear.
   *
   * This determines whether [publishTo] should publish the staged screen or clear a previous
   * leaseholder's screen. A null staged value alone cannot distinguish an explicit clear from a
   * screen that has never been updated.
   */
  private var hasScreenUpdate = false

  /**
   * Whether this instance has received a navigation context update, including an explicit clear.
   *
   * This determines whether [publishTo] should publish the staged context or clear a previous
   * leaseholder's context, even if the staged scope no longer contains the key.
   */
  private var hasNavigationContextUpdate = false

  /**
   * The latest breadcrumb received i) before the first breadcrumb is published and ii) while not
   * holding the navigation lease.
   *
   * This class implements a breadcrumb buffering policy that:
   *
   * 1. tracks the latest breadcrumb received prior to initial navigation lease acquisition;
   * 2. publishes that breadcrumb upon initial lease acquisition; and
   * 3. discards all breadcrumbs received after its first breadcrumb has been published when the
   *    navigation lease isn't held.
   *
   * That policy gives us the following behavior:
   *
   * - Breadcrumbs staged before initial lease acquisition retain only the latest destination.
   *   Normally the first prepared update acquires the lease before emitting its breadcrumb, so it
   *   is published immediately.
   *
   * - Suppose the `SentryNavEffect` that publishes `Profile` pauses and loses the navigation lease.
   *   Another effect takes over and records a `Settings` back stack entry. If the paused effect
   *   buffers new breadcrumbs while it lacks the lease, it might later replay (an unseen)
   *   `Checkout` when it resumes, even though `Settings` was already reported by the active effect.
   *   Discarding those later breadcrumbs avoids replaying history that may be stale or out of
   *   order.
   */
  private var pendingInitialBreadcrumb: (() -> Unit)? = null

  /**
   * Whether this instance has ever published a breadcrumb.
   *
   * This remains true across pauses, so breadcrumbs received without the navigation lease after the
   * first publication are discarded rather than replayed as stale navigation history on resume.
   */
  private var hasPublishedBreadcrumb: Boolean = false

  private val screenName: String?
    get() = scope.screen

  private val contexts
    get() = scope.contexts

  var propagationContext: PropagationContext
    get() = scope.propagationContext
    set(value) {
      scope.propagationContext = PropagationContext(value)
      hasPropagationContextUpdate = true
    }

  fun getScreen(realScope: IScope, ownsLease: Boolean): String? =
    if (ownsLease) realScope.screen else screenName

  fun setScreen(screen: String?, realScope: IScope, ownsLease: Boolean) {
    scope.screen = screen
    hasScreenUpdate = true

    if (ownsLease) {
      publishScreen(screen, realScope)
    }
  }

  fun setNavigationContext(value: Any?, realScope: IScope, ownsLease: Boolean) {
    scope.setContexts(NAVIGATION_CONTEXT_KEY, value)
    hasNavigationContextUpdate = true

    if (ownsLease) {
      publishNavigationContext(value, realScope)
    }
  }

  fun publishTo(realScope: IScope) {
    // A new leaseholder publishes a complete navigation state. Clearing fields it hasn't staged
    // prevents the previous leaseholder's values from lingering after a handoff.
    publishScreen(if (hasScreenUpdate) screenName else null, realScope)
    publishNavigationContext(
      if (hasNavigationContextUpdate) contexts[NAVIGATION_CONTEXT_KEY] else null,
      realScope,
    )
    if (hasPropagationContextUpdate) {
      realScope.propagationContext = PropagationContext(scope.propagationContext)
    }
  }

  private fun publishScreen(screen: String?, realScope: IScope) {
    publishedScreen = screen
    realScope.screen = screen
  }

  private fun publishNavigationContext(value: Any?, realScope: IScope) {
    publishedNavigationContext = value
    realScope.setContexts(NAVIGATION_CONTEXT_KEY, value)
  }

  /** Called only for the last publishing session; releasing its lease alone does not clear data. */
  fun clearPublishedFrom(realScope: IScope) {
    if (publishedScreen != null && realScope.screen === publishedScreen) {
      realScope.screen = null
    }
    if (
      publishedNavigationContext != null &&
        realScope.contexts[NAVIGATION_CONTEXT_KEY] === publishedNavigationContext
    ) {
      // Use setContexts so scope observers also receive the removal.
      realScope.setContexts(NAVIGATION_CONTEXT_KEY, null as Any?)
    }
    publishedScreen = null
    publishedNavigationContext = null
  }

  fun withPropagationContext(callback: Scope.IWithPropagationContext): PropagationContext =
    scope.withPropagationContext(callback).also { hasPropagationContextUpdate = true }

  /** Publishes the breadcrumb when leased, or buffers it until the first publication. */
  fun addBreadcrumb(ownsLease: Boolean, publish: () -> Unit) {
    if (ownsLease) {
      publish()
      hasPublishedBreadcrumb = true
      pendingInitialBreadcrumb = null
    } else if (!hasPublishedBreadcrumb) {
      // Replace older unpublished breadcrumbs so only the latest is replayed on first lease.
      pendingInitialBreadcrumb = publish
    }
  }

  /**
   * Publishes the buffered initial breadcrumb if this instance currently owns the navigation lease.
   */
  fun publishPendingBreadcrumb(ownsLease: Boolean) {
    val pending = pendingInitialBreadcrumb ?: return
    if (ownsLease) {
      pending()
      hasPublishedBreadcrumb = true
      pendingInitialBreadcrumb = null
    }
  }
}

/** One navigation owner, with transaction lifetimes tracked separately for handoff and rollback. */
// Keep lease transitions and transaction cleanup in one state machine.
@Suppress("TooManyFunctions")
internal class NavLeaseCoordinator {
  private class Candidate(
    val owner: Any,
    val publish: () -> Unit,
    val clearPublishedData: (IScope) -> Unit,
  ) {
    var isStarted: Boolean = false
    var resumedAt: Long = 0
    var isActive: Boolean = false
    var isPaused: Boolean = false
    var transaction: ITransaction? = null
  }

  private val candidates = mutableListOf<Candidate>()
  private var resumeSequence: Long = 0
  private var leaseHolder: Candidate? = null

  // Publication provenance survives pause, but grants no authority to write new data.
  private var publishedDataCandidate: Candidate? = null

  // A paused session can retain its running transaction even when nobody holds the lease.
  // Other candidates' transactions remain detached until restored, replaced, or disposed.
  private var activeTransactionCandidate: Candidate? = null

  fun hasLease(owner: Any): Boolean = leaseHolder?.owner === owner

  fun registerCandidate(
    owner: Any,
    isResumed: Boolean,
    isStarted: Boolean = isResumed,
    clearPublishedData: (IScope) -> Unit = {},
    publish: () -> Unit,
  ) {
    if (findCandidate(owner) == null) {
      candidates +=
        Candidate(owner, publish, clearPublishedData).apply {
          this.isStarted = isStarted
          resumedAt = if (isResumed) ++resumeSequence else 0
        }
    }
  }

  /** Acquires authority on the first prepared update, never on subsequent background updates. */
  fun activateCandidate(owner: Any, scope: IScope): (() -> Unit)? {
    val candidate = findCandidate(owner) ?: return null
    if (candidate.isActive) {
      return null
    }
    candidate.isActive = true
    return if (candidate.isPaused) null else transferLeaseTo(candidate, scope)
  }

  fun resumeCandidate(owner: Any, scope: IScope): (() -> Unit)? {
    val candidate = findCandidate(owner) ?: return null
    candidate.isStarted = true
    candidate.isPaused = false
    candidate.resumedAt = ++resumeSequence
    return if (candidate.isActive) transferLeaseTo(candidate, scope) else null
  }

  fun pauseCandidate(owner: Any, scope: IScope): (() -> Unit)? {
    val candidate = findCandidate(owner) ?: return null
    candidate.isPaused = true
    return if (leaseHolder === candidate) {
      transferLeaseTo(mostRecentlyResumedCandidate(excluding = candidate), scope)
    } else null
  }

  fun startCandidate(owner: Any) {
    // Becoming visible restores fallback eligibility, but only resume or explicit restoration
    // grants navigation authority. A never-resumed incoming session is not a rollback target.
    findCandidate(owner)?.isStarted = true
  }

  fun stopCandidate(owner: Any, scope: IScope): (() -> Unit)? {
    val candidate = findCandidate(owner) ?: return null
    candidate.isStarted = false
    candidate.isPaused = true
    return if (leaseHolder === candidate) transferLeaseTo(mostRecentlyResumedCandidate(), scope)
    else null
  }

  fun unregisterCandidate(owner: Any, scope: IScope): (() -> Unit)? {
    val candidate = findCandidate(owner) ?: return null
    finishTransaction(candidate, scope)
    candidates.remove(candidate)
    if (publishedDataCandidate === candidate) {
      publishedDataCandidate = null
      candidate.clearPublishedData(scope)
    }
    return if (leaseHolder === candidate) transferLeaseTo(mostRecentlyResumedCandidate(), scope)
    else null
  }

  fun bindTransaction(owner: Any, transaction: ITransaction) {
    val candidate = leaseHolder ?: return
    if (candidate.owner === owner) {
      candidate.transaction = transaction
      activeTransactionCandidate = candidate
    }
  }

  private fun findCandidate(owner: Any): Candidate? = candidates.firstOrNull { it.owner === owner }

  private fun mostRecentlyResumedCandidate(excluding: Candidate? = null): Candidate? =
    candidates
      .filter { it !== excluding && it.isActive && it.isStarted && it.resumedAt > 0 }
      .maxByOrNull { it.resumedAt }

  private fun transferLeaseTo(candidate: Candidate?, scope: IScope): (() -> Unit)? {
    if (leaseHolder === candidate) {
      return null
    }

    if (candidate != null) {
      val previous = activeTransactionCandidate
      if (previous != null && previous !== candidate) {
        clearTransactionFromScope(previous, scope)
        activeTransactionCandidate = null
      }

      val transaction = candidate.transaction
      if (transaction?.isFinished == true) {
        clearTransactionFromScope(candidate, scope)
        candidate.transaction = null
        if (activeTransactionCandidate === candidate) {
          activeTransactionCandidate = null
        }
      } else if (transaction != null) {
        scope.withTransaction { current ->
          if (current == null || current === transaction) {
            scope.transaction = transaction
            activeTransactionCandidate = candidate
          }
        }
      }
    }

    leaseHolder = candidate
    return candidate?.let {
      {
        if (leaseHolder === candidate) {
          publishedDataCandidate = candidate
          candidate.publish()
        }
      }
    }
  }

  private fun finishTransaction(candidate: Candidate, scope: IScope) {
    val transaction = candidate.transaction
    if (transaction != null && !transaction.isFinished) {
      transaction.finish(transaction.status ?: SpanStatus.OK)
    }
    clearTransactionFromScope(candidate, scope)
    candidate.transaction = null
    if (activeTransactionCandidate === candidate) {
      activeTransactionCandidate = null
    }
  }

  private fun clearTransactionFromScope(candidate: Candidate, scope: IScope) {
    val transaction = candidate.transaction ?: return
    scope.withTransaction { current ->
      if (current === transaction) {
        scope.clearTransaction()
      }
    }
  }
}

internal val defaultNavLeaseCoordinator: NavLeaseCoordinator = NavLeaseCoordinator()
