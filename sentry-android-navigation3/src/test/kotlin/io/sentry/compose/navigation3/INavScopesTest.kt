package io.sentry.compose.navigation3

import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.ITransaction
import io.sentry.PropagationContext
import io.sentry.ScopeType
import io.sentry.SpanStatus
import io.sentry.TransactionContext
import io.sentry.TransactionOptions
import org.junit.Test

/**
 * The tests in this suite use the following terminology:
 *
 * - **Navigation data**: The non-transaction Sentry data emitted by [SentryNavEffect], i.e., screen
 *   names, back stack context, and breadcrumbs.
 *
 * - **Navigation state**: Navigation data that has a single, variable-like value, i.e., screen
 *   names and back stack context.
 *
 * - **Navigation events**: Navigation data that has accumulated, event-like values, i.e.,
 *   breadcrumbs.
 */
@Suppress("LargeClass")
class INavScopesTest {

  @Test
  fun `initial back stack update publishes navigation state before lifecycle resumes`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    val owner = NavTestLifecycle(state = Lifecycle.State.CREATED)
    fixture.scope.screen = "visible"

    navScopes.attach(owner.lifecycle)
    navScopes.runBackStackUpdate {
      navScopes.configureScope {
        it.screen = "pending"
        it.setContexts(NAVIGATION_CONTEXT_KEY, mapOf("route" to "pending"))
      }
    }

    assertThat(fixture.scope.screen).isEqualTo("pending")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("route" to "pending"))
  }

  @Test
  fun `navigation state is staged after lifecycle leaves a resumed state`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)

    navScopes.attach(owner.lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope { it.screen = "Home" } }
    assertThat(fixture.scope.screen).isEqualTo("Home")

    owner.pause()

    navScopes.configureScope { it.screen = "Profile" }
    assertThat(fixture.scope.screen).isEqualTo("Home")

    owner.resume()

    assertThat(fixture.scope.screen).isEqualTo("Profile")
  }

  @Test
  fun `navigation state is published immediately when lifecycle begins in a resumed state`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()

    navScopes.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    navScopes.runBackStackUpdate {
      navScopes.configureScope {
        it.screen = "Home"
        it.setContexts(NAVIGATION_CONTEXT_KEY, mapOf("route" to "Home"))
      }
    }

    assertThat(fixture.scope.screen).isEqualTo("Home")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isEqualTo(mapOf("route" to "Home"))
  }

  @Test
  fun `incoming owner publishes nav state after its initial update before lifecycle resumes`() {
    val fixture = NavTestFixture()
    val firstNavScopes = fixture.navScopes()
    firstNavScopes.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    val homeBackStack = mapOf("Back Stack" to listOf(mapOf("entry" to "/Home")))
    firstNavScopes.runBackStackUpdate {
      firstNavScopes.configureScope {
        it.screen = "Home"
        it.setContexts(NAVIGATION_CONTEXT_KEY, homeBackStack)
      }
    }
    val secondNavScopes = fixture.navScopes()
    val secondOwner = NavTestLifecycle(Lifecycle.State.CREATED)
    secondNavScopes.attach(secondOwner.lifecycle)
    val settingsBackStack = mapOf("Back Stack" to listOf(mapOf("entry" to "/Settings")))

    secondNavScopes.runBackStackUpdate {
      secondNavScopes.configureScope {
        it.screen = "Settings"
        it.setContexts(NAVIGATION_CONTEXT_KEY, settingsBackStack)
      }
    }

    assertThat(fixture.scope.screen).isEqualTo("Settings")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isEqualTo(settingsBackStack)

    secondOwner.resume()

    assertThat(fixture.scope.screen).isEqualTo("Settings")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isEqualTo(settingsBackStack)
  }

  @Test
  fun `cleaning up the previous owner doesn't modify the current navigation state`() {
    val fixture = NavTestFixture()
    val firstNavScopes = fixture.navScopes()
    val firstOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    firstNavScopes.attach(firstOwner.lifecycle)
    firstNavScopes.runBackStackUpdate {
      firstNavScopes.configureScope {
        it.screen = "Home"
        it.setContexts(
          NAVIGATION_CONTEXT_KEY,
          mapOf("Back Stack" to listOf(mapOf("entry" to "/Home"))),
        )
      }
    }
    val secondNavScopes = fixture.navScopes()
    secondNavScopes.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    val settingsBackStack = mapOf("Back Stack" to listOf(mapOf("entry" to "/Settings")))
    secondNavScopes.runBackStackUpdate {
      secondNavScopes.configureScope {
        it.screen = "Settings"
        it.setContexts(NAVIGATION_CONTEXT_KEY, settingsBackStack)
      }
    }

    firstNavScopes.configureScope {
      it.screen = "unseen"
      it.setContexts(
        NAVIGATION_CONTEXT_KEY,
        mapOf("Back Stack" to listOf(mapOf("entry" to "/Unseen"))),
      )
    }
    assertThat(fixture.scope.screen).isEqualTo("Settings")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isEqualTo(settingsBackStack)

    firstOwner.pause()
    assertThat(fixture.scope.screen).isEqualTo("Settings")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isEqualTo(settingsBackStack)

    firstNavScopes.dispose()
    assertThat(fixture.scope.screen).isEqualTo("Settings")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isEqualTo(settingsBackStack)

    val profileBackStack = mapOf("Back Stack" to listOf(mapOf("entry" to "/Profile")))
    secondNavScopes.configureScope {
      it.screen = "Profile"
      it.setContexts(NAVIGATION_CONTEXT_KEY, profileBackStack)
    }

    assertThat(fixture.scope.screen).isEqualTo("Profile")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isEqualTo(profileBackStack)
  }

  @Test
  fun `disposing incoming owner restores previous owners complete navigation state`() {
    val fixture = NavTestFixture()
    val previousNavScopes = fixture.navScopes()
    previousNavScopes.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    previousNavScopes.runBackStackUpdate {
      previousNavScopes.configureScope { it.screen = "Home" }
    }
    val incomingNavScopes = fixture.navScopes()
    incomingNavScopes.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    incomingNavScopes.runBackStackUpdate {
      incomingNavScopes.configureScope {
        it.screen = "Settings"
        it.setContexts(NAVIGATION_CONTEXT_KEY, mapOf("route" to "Settings"))
      }
    }
    assertThat(fixture.scope.screen).isEqualTo("Settings")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("route" to "Settings"))

    incomingNavScopes.dispose()

    assertThat(fixture.scope.screen).isEqualTo("Home")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isNull()
  }

  @Test
  fun `a new owner clears any navigation state it didn't stage`() {
    val fixture = NavTestFixture()
    fixture.scope.screen = "old"
    fixture.scope.setContexts(NAVIGATION_CONTEXT_KEY, mapOf("route" to "old"))
    fixture.scope.setContexts("host", mapOf("value" to "retained"))

    val navScopes = fixture.navScopes()
    navScopes.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope {} }

    assertThat(fixture.scope.screen).isNull()
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isNull()
    assertThat(fixture.scope.contexts["host"]).isEqualTo(mapOf("value" to "retained"))
  }

  @Test
  fun `disposing an older publisher preserves identical state published by a later paused owner`() {
    val fixture = NavTestFixture()
    val first = fixture.navScopes()
    val firstOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    val second = fixture.navScopes()
    val secondOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    val sharedContext = mapOf("route" to "Home")
    first.attach(firstOwner.lifecycle)
    first.runBackStackUpdate {
      first.configureScope {
        it.screen = "Home"
        it.setContexts(NAVIGATION_CONTEXT_KEY, sharedContext)
      }
    }
    second.attach(secondOwner.lifecycle)
    second.runBackStackUpdate {
      second.configureScope {
        it.screen = "Home"
        it.setContexts(NAVIGATION_CONTEXT_KEY, sharedContext)
      }
    }
    firstOwner.stop()
    secondOwner.pause()

    first.dispose()

    assertThat(fixture.scope.screen).isEqualTo("Home")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isSameInstanceAs(sharedContext)

    second.dispose()

    assertThat(fixture.scope.screen).isNull()
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isNull()
  }

  @Test
  fun `disposing before the first prepared update preserves existing state without publishing pending breadcrumbs`() {
    val fixture = NavTestFixture()
    fixture.scope.screen = "Host"
    val hostContext = mapOf("route" to "Host")
    fixture.scope.setContexts(NAVIGATION_CONTEXT_KEY, hostContext)
    fixture.scopes.addBreadcrumb(Breadcrumb().apply { message = "Host" })
    val navScopes = fixture.navScopes()
    navScopes.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    navScopes.addBreadcrumb(Breadcrumb().apply { message = "Pending" })

    navScopes.dispose()

    assertThat(fixture.scope.screen).isEqualTo("Host")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isSameInstanceAs(hostContext)
    assertThat(fixture.scope.breadcrumbs.map { it.message }).containsExactly("Host")
  }

  @Test
  fun `cleared navigation state is published as empty state when lifecycle resumes`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)

    navScopes.attach(owner.lifecycle)
    navScopes.runBackStackUpdate {
      navScopes.configureScope {
        it.screen = "Home"
        it.setContexts(NAVIGATION_CONTEXT_KEY, mapOf("route" to "Home"))
      }
    }
    owner.pause()
    navScopes.configureScope {
      it.screen = null
      it.setContexts(NAVIGATION_CONTEXT_KEY, null as Any?)
    }

    owner.resume()

    assertThat(fixture.scope.screen).isNull()
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isNull()
  }

  @Test
  fun `the latest initial breadcrumb is published after the first back stack update`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    val owner = NavTestLifecycle()

    navScopes.attach(owner.lifecycle)
    navScopes.addBreadcrumb(Breadcrumb().apply { message = "Home" })
    navScopes.addBreadcrumb(Breadcrumb().apply { message = "Profile" }, Hint())
    assertThat(fixture.scope.breadcrumbs).isEmpty()

    navScopes.runBackStackUpdate { navScopes.configureScope {} }

    assertThat(fixture.scope.breadcrumbs.map { it.message }).containsExactly("Profile")
  }

  @Test
  fun `breadcrumbs received after publication are discarded if lifecycle is not resumed`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)

    navScopes.attach(owner.lifecycle)
    navScopes.runBackStackUpdate {
      navScopes.configureScope {}
      navScopes.addBreadcrumb(Breadcrumb().apply { message = "Home" }, Hint())
    }
    assertThat(fixture.scope.breadcrumbs.map { it.message }).containsExactly("Home").inOrder()

    owner.pause()

    navScopes.addBreadcrumb(Breadcrumb().apply { message = "first_unseen" })
    navScopes.addBreadcrumb(Breadcrumb().apply { message = "second_unseen" })
    assertThat(fixture.scope.breadcrumbs.map { it.message }).containsExactly("Home").inOrder()
  }

  @Test
  fun `breadcrumbs received after publication are published if lifecycle is resumed`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)

    navScopes.attach(owner.lifecycle)
    navScopes.runBackStackUpdate {
      navScopes.configureScope {}
      navScopes.addBreadcrumb(Breadcrumb().apply { message = "Home" }, Hint())
    }
    assertThat(fixture.scope.breadcrumbs.map { it.message }).containsExactly("Home").inOrder()

    owner.pause()

    navScopes.addBreadcrumb(Breadcrumb().apply { message = "unseen" })
    assertThat(fixture.scope.breadcrumbs.map { it.message }).containsExactly("Home").inOrder()

    owner.resume()

    navScopes.addBreadcrumb(Breadcrumb().apply { message = "Profile" })
    assertThat(fixture.scope.breadcrumbs.map { it.message })
      .containsExactly("Home", "Profile")
      .inOrder()
  }

  @Test
  fun `only the current owner of the navigation lease publishes breadcrumbs during overlap`() {
    val fixture = NavTestFixture()
    val firstNavScopes = fixture.navScopes()
    val firstOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    firstNavScopes.attach(firstOwner.lifecycle)
    firstNavScopes.runBackStackUpdate {
      firstNavScopes.configureScope {}
      firstNavScopes.addBreadcrumb(Breadcrumb().apply { message = "Home" })
    }
    val secondNavScopes = fixture.navScopes()
    val secondOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    secondNavScopes.attach(secondOwner.lifecycle)

    secondNavScopes.runBackStackUpdate {
      secondNavScopes.configureScope {}
      secondNavScopes.addBreadcrumb(Breadcrumb().apply { message = "Settings" }, Hint())
    }
    firstNavScopes.addBreadcrumb(Breadcrumb().apply { message = "unseen" }, Hint())

    assertThat(fixture.scope.breadcrumbs.map { it.message })
      .containsExactly("Home", "Settings")
      .inOrder()

    firstOwner.pause()
    secondOwner.pause()
    firstOwner.resume()

    assertThat(fixture.scope.breadcrumbs.map { it.message })
      .containsExactly("Home", "Settings")
      .inOrder()
    firstNavScopes.addBreadcrumb(Breadcrumb().apply { message = "Profile" })
    assertThat(fixture.scope.breadcrumbs.map { it.message })
      .containsExactly("Home", "Settings", "Profile")
      .inOrder()
  }

  @Test
  fun `each incoming navigation lease owner publishes only its own latest pending initial breadcrumb`() {
    val fixture = NavTestFixture()
    val firstNavScopes = fixture.navScopes()
    val firstOwner = NavTestLifecycle(Lifecycle.State.CREATED)
    firstNavScopes.attach(firstOwner.lifecycle)
    val secondNavScopes = fixture.navScopes()
    val secondOwner = NavTestLifecycle(Lifecycle.State.CREATED)
    secondNavScopes.attach(secondOwner.lifecycle)

    firstNavScopes.addBreadcrumb(Breadcrumb().apply { message = "first initial" })
    secondNavScopes.addBreadcrumb(Breadcrumb().apply { message = "second initial" }, Hint())
    firstNavScopes.addBreadcrumb(Breadcrumb().apply { message = "first latest" }, Hint())
    secondNavScopes.addBreadcrumb(Breadcrumb().apply { message = "second latest" })
    assertThat(fixture.scope.breadcrumbs).isEmpty()

    firstOwner.resume()
    firstNavScopes.runBackStackUpdate { firstNavScopes.configureScope {} }

    assertThat(fixture.scope.breadcrumbs.map { it.message }).containsExactly("first latest")

    firstOwner.pause()
    secondOwner.resume()
    secondNavScopes.runBackStackUpdate { secondNavScopes.configureScope {} }

    assertThat(fixture.scope.breadcrumbs.map { it.message })
      .containsExactly("first latest", "second latest")
      .inOrder()
  }

  @Test
  fun `navigation transactions cannot start without the navigation lease`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()

    val transaction =
      navScopes.startTransaction(TransactionContext("Home", "navigation"), TransactionOptions())

    assertThat(transaction.isNoOp).isTrue()
    assertThat(fixture.scope.transaction).isNull()
  }

  @Test
  fun `navigation transactions can start before lifecycle is resumed`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    navScopes.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope {} }

    val transaction =
      navScopes.startTransaction(TransactionContext("Home", "navigation"), TransactionOptions())

    assertThat(transaction.isNoOp).isFalse()
    assertThat(transaction.name).isEqualTo("Home")
    assertThat(transaction.operation).isEqualTo("navigation")
    assertThat(transaction.isFinished).isFalse()
  }

  @Test
  fun `navigation transactions cannot start while lifecycle is paused`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)
    navScopes.attach(owner.lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope {} }

    owner.pause()
    val transaction =
      navScopes.startTransaction(TransactionContext("Profile", "navigation"), TransactionOptions())

    assertThat(transaction.isNoOp).isTrue()
  }

  @Test
  fun `navigation transactions cannot start after another instance takes the navigation lease`() {
    val fixture = NavTestFixture()
    val firstNavScopes = fixture.navScopes()
    firstNavScopes.attach(NavTestLifecycle().lifecycle)
    firstNavScopes.runBackStackUpdate { firstNavScopes.configureScope {} }
    val secondNavScopes = fixture.navScopes()
    secondNavScopes.attach(NavTestLifecycle().lifecycle)
    secondNavScopes.runBackStackUpdate { secondNavScopes.configureScope {} }
    val currentTransaction =
      secondNavScopes.startTransaction(
        TransactionContext("Settings", "navigation"),
        TransactionOptions(),
      )
    secondNavScopes.configureScope { it.transaction = currentTransaction }

    val rejectedTransaction =
      firstNavScopes.startTransaction(
        TransactionContext("Profile", "navigation"),
        TransactionOptions(),
      )

    assertThat(rejectedTransaction.isNoOp).isTrue()
    assertThat(currentTransaction.isNoOp).isFalse()
    assertThat(currentTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(currentTransaction)
  }

  @Test
  fun `navigation state updates from the previous owner don't modify the current transaction`() {
    val fixture = NavTestFixture()
    val firstNavScopes = fixture.navScopes()
    firstNavScopes.attach(NavTestLifecycle().lifecycle)
    firstNavScopes.runBackStackUpdate { firstNavScopes.configureScope {} }
    val previousTransaction =
      firstNavScopes.startTransaction(
        TransactionContext("Home", "navigation"),
        TransactionOptions(),
      )
    firstNavScopes.configureScope { it.transaction = previousTransaction }
    val secondNavScopes = fixture.navScopes()
    secondNavScopes.attach(NavTestLifecycle().lifecycle)
    secondNavScopes.runBackStackUpdate { secondNavScopes.configureScope {} }
    val currentTransaction =
      secondNavScopes.startTransaction(
        TransactionContext("Settings", "navigation"),
        TransactionOptions(),
      )
    secondNavScopes.configureScope { it.transaction = currentTransaction }

    firstNavScopes.runBackStackUpdate {
      firstNavScopes.configureScope { it.screen = "unseen" }
    }

    assertThat(currentTransaction.isNoOp).isFalse()
    assertThat(currentTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(currentTransaction)
  }

  @Test
  fun `navigation transaction handoff waits for a back stack update`() {
    val fixture = NavTestFixture()
    val firstNavScopes = fixture.navScopes()
    firstNavScopes.attach(NavTestLifecycle().lifecycle)
    firstNavScopes.runBackStackUpdate { firstNavScopes.configureScope {} }
    val transaction =
      firstNavScopes.startTransaction(
        TransactionContext("Home", "navigation"),
        TransactionOptions(),
      )
    firstNavScopes.configureScope { it.transaction = transaction }
    val secondNavScopes = fixture.navScopes()

    secondNavScopes.attach(NavTestLifecycle().lifecycle)
    secondNavScopes.configureScope { it.setTag("host", "value") }

    assertThat(transaction.isNoOp).isFalse()
    assertThat(transaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(transaction)
  }

  @Test
  fun `navigation transaction handoff suspends and unbinds the previous transaction before applying the update`() {
    val fixture = NavTestFixture()
    val firstNavScopes = fixture.navScopes()
    firstNavScopes.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    firstNavScopes.runBackStackUpdate { firstNavScopes.configureScope {} }
    val previousTransaction =
      firstNavScopes.startTransaction(
        TransactionContext("Home", "navigation"),
        TransactionOptions(),
      )
    previousTransaction.status = SpanStatus.CANCELLED
    firstNavScopes.configureScope { it.transaction = previousTransaction }
    val secondNavScopes = fixture.navScopes()
    secondNavScopes.attach(NavTestLifecycle().lifecycle)
    var previousWasSuspendedDuringUpdate = false
    var scopeWasEmptyDuringUpdate = false
    var replacementTransaction: ITransaction? = null

    secondNavScopes.runBackStackUpdate {
      secondNavScopes.configureScope {
        previousWasSuspendedDuringUpdate = !previousTransaction.isFinished
        scopeWasEmptyDuringUpdate = it.transaction == null
        replacementTransaction =
          secondNavScopes.startTransaction(
            TransactionContext("Settings", "navigation"),
            TransactionOptions(),
          )
        it.transaction = replacementTransaction
      }
    }

    assertThat(previousWasSuspendedDuringUpdate).isTrue()
    assertThat(scopeWasEmptyDuringUpdate).isTrue()
    assertThat(previousTransaction.status).isEqualTo(SpanStatus.CANCELLED)
    val replacement = requireNotNull(replacementTransaction)
    assertThat(replacement.isNoOp).isFalse()
    assertThat(replacement.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(replacement)
  }

  @Test
  fun `disposing incoming owner restores the previous owners suspended transaction`() {
    val fixture = NavTestFixture()
    val previousOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    val previousNavScopes = fixture.navScopes()
    previousNavScopes.attach(previousOwner.lifecycle)
    previousNavScopes.runBackStackUpdate { previousNavScopes.configureScope {} }
    val previousTransaction =
      previousNavScopes.startTransaction(
        TransactionContext("Home", "navigation"),
        TransactionOptions(),
      )
    previousNavScopes.configureScope { it.transaction = previousTransaction }
    val incomingNavScopes = fixture.navScopes()
    incomingNavScopes.attach(NavTestLifecycle().lifecycle)
    lateinit var incomingTransaction: ITransaction
    incomingNavScopes.runBackStackUpdate {
      incomingNavScopes.configureScope {
        incomingTransaction =
          incomingNavScopes.startTransaction(
            TransactionContext("Settings", "navigation"),
            TransactionOptions(),
          )
        it.transaction = incomingTransaction
      }
    }

    incomingNavScopes.dispose()

    assertThat(incomingTransaction.isFinished).isTrue()
    assertThat(previousTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(previousTransaction)
  }

  @Test
  fun `stopping previous owner retains its suspended transaction until it returns`() {
    val fixture = NavTestFixture()
    val previousOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    val previousNavScopes = fixture.navScopes()
    previousNavScopes.attach(previousOwner.lifecycle)
    previousNavScopes.runBackStackUpdate { previousNavScopes.configureScope {} }
    val previousTransaction =
      previousNavScopes.startTransaction(
        TransactionContext("Home", "navigation"),
        TransactionOptions(),
      )
    previousNavScopes.configureScope { it.transaction = previousTransaction }
    val incomingNavScopes = fixture.navScopes()
    incomingNavScopes.attach(NavTestLifecycle().lifecycle)
    lateinit var incomingTransaction: ITransaction
    incomingNavScopes.runBackStackUpdate {
      incomingNavScopes.configureScope {
        incomingTransaction =
          incomingNavScopes.startTransaction(
            TransactionContext("Settings", "navigation"),
            TransactionOptions(),
          )
        it.transaction = incomingTransaction
      }
    }

    previousOwner.stop()
    incomingNavScopes.dispose()

    assertThat(previousTransaction.isFinished).isFalse()
    assertThat(incomingTransaction.isFinished).isTrue()
    assertThat(fixture.scope.transaction).isNull()

    previousOwner.resume()

    assertThat(fixture.scope.transaction).isSameInstanceAs(previousTransaction)
  }

  @Test
  fun `pausing before incoming update retains the existing transaction without navigation authority`() {
    val fixture = NavTestFixture()
    val previousOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    val previousNavScopes = fixture.navScopes()
    previousNavScopes.attach(previousOwner.lifecycle)
    previousNavScopes.runBackStackUpdate { previousNavScopes.configureScope {} }
    val previousTransaction =
      previousNavScopes.startTransaction(
        TransactionContext("Home", "navigation"),
        TransactionOptions(),
      )
    previousNavScopes.configureScope { it.transaction = previousTransaction }
    val incomingNavScopes = fixture.navScopes()
    incomingNavScopes.attach(NavTestLifecycle().lifecycle)

    previousOwner.pause()
    incomingNavScopes.dispose()

    assertThat(previousTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(previousTransaction)
    assertThat(
        previousNavScopes
          .startTransaction(
            TransactionContext("Profile", "navigation"),
            TransactionOptions(),
          )
          .isNoOp
      )
      .isTrue()
  }

  @Test
  fun `navigation transaction disposal finishes and unbinds the owned transaction`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    navScopes.attach(NavTestLifecycle().lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope {} }
    val transaction =
      navScopes.startTransaction(TransactionContext("Home", "navigation"), TransactionOptions())
    navScopes.configureScope { it.transaction = transaction }

    navScopes.dispose()

    assertThat(transaction.isNoOp).isFalse()
    assertThat(transaction.isFinished).isTrue()
    assertThat(transaction.status).isEqualTo(SpanStatus.OK)
    assertThat(fixture.scope.transaction).isNull()
  }

  @Test
  fun `navigation transaction disposal preserves a host transaction that replaced it`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    navScopes.attach(NavTestLifecycle().lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope {} }
    val navigationTransaction =
      navScopes.startTransaction(TransactionContext("Home", "navigation"), TransactionOptions())
    navScopes.configureScope { it.transaction = navigationTransaction }
    val hostTransaction = fixture.transaction("Host")
    fixture.scope.transaction = hostTransaction

    navScopes.dispose()

    assertThat(navigationTransaction.isFinished).isTrue()
    assertThat(hostTransaction.isNoOp).isFalse()
    assertThat(hostTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(hostTransaction)
  }

  @Test
  fun `navigation transaction disposal by the former owner preserves the current owners transaction`() {
    val fixture = NavTestFixture()
    val firstNavScopes = fixture.navScopes()
    firstNavScopes.attach(NavTestLifecycle().lifecycle)
    firstNavScopes.runBackStackUpdate { firstNavScopes.configureScope {} }
    val previousTransaction =
      firstNavScopes.startTransaction(
        TransactionContext("Home", "navigation"),
        TransactionOptions(),
      )
    firstNavScopes.configureScope { it.transaction = previousTransaction }
    val secondNavScopes = fixture.navScopes()
    secondNavScopes.attach(NavTestLifecycle().lifecycle)
    secondNavScopes.runBackStackUpdate { secondNavScopes.configureScope {} }
    val currentTransaction =
      secondNavScopes.startTransaction(
        TransactionContext("Settings", "navigation"),
        TransactionOptions(),
      )
    secondNavScopes.configureScope { it.transaction = currentTransaction }

    firstNavScopes.dispose()

    assertThat(currentTransaction.isNoOp).isFalse()
    assertThat(currentTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(currentTransaction)
  }

  @Test
  fun `navigation transaction ownership stays with the active owner if the incoming owner disposes before an update`() {
    val fixture = NavTestFixture()
    val firstNavScopes = fixture.navScopes()
    firstNavScopes.attach(NavTestLifecycle().lifecycle)
    firstNavScopes.runBackStackUpdate { firstNavScopes.configureScope {} }
    val activeTransaction =
      firstNavScopes.startTransaction(
        TransactionContext("Home", "navigation"),
        TransactionOptions(),
      )
    firstNavScopes.configureScope { it.transaction = activeTransaction }
    val secondNavScopes = fixture.navScopes()
    secondNavScopes.attach(NavTestLifecycle().lifecycle)

    secondNavScopes.dispose()

    assertThat(activeTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(activeTransaction)
    val nextTransaction =
      firstNavScopes.startTransaction(
        TransactionContext("Profile", "navigation"),
        TransactionOptions(),
      )
    assertThat(nextTransaction.isNoOp).isFalse()
    assertThat(nextTransaction.name).isEqualTo("Profile")
  }

  @Test
  fun `the previous owner's transaction remains active when a navigation update fails`() {
    val fixture = NavTestFixture()

    val firstNavScopesInstance = fixture.navScopes()
    firstNavScopesInstance.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    firstNavScopesInstance.runBackStackUpdate {
      firstNavScopesInstance.configureScope { it.screen = "Home" }
    }
    val tx =
      firstNavScopesInstance.startTransaction(
        TransactionContext("Home", "navigation"),
        TransactionOptions(),
      )
    fixture.scope.transaction = tx

    val secondNavScopesInstance = fixture.navScopes()
    secondNavScopesInstance.attach(NavTestLifecycle().lifecycle)

    val exception =
      try {
        secondNavScopesInstance.runBackStackUpdate { throw IllegalArgumentException("mapper") }
        null
      } catch (e: IllegalArgumentException) {
        e
      }
    assertThat(exception).isInstanceOf(IllegalArgumentException::class.java)

    secondNavScopesInstance.configureScope { it.setTag("host", "value") }

    assertThat(tx.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(tx)
    assertThat(fixture.scope.screen).isEqualTo("Home")
  }

  @Test
  fun `navigation lease survives a no-op returned while tracing is disabled`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    navScopes.attach(NavTestLifecycle().lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope {} }
    fixture.options.setTracesSampleRate(null)

    val disabledTransaction =
      navScopes.startTransaction(TransactionContext("Home", "navigation"), TransactionOptions())
    assertThat(disabledTransaction.isNoOp).isTrue()

    fixture.options.setTracesSampleRate(1.0)
    val enabledTransaction =
      navScopes.startTransaction(TransactionContext("Profile", "navigation"), TransactionOptions())
    assertThat(enabledTransaction.isNoOp).isFalse()
    assertThat(enabledTransaction.name).isEqualTo("Profile")
  }

  @Test
  fun `propagation changes are not published without the navigation lease`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    navScopes.attach(NavTestLifecycle().lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope {} }
    val incoming = fixture.navScopes()
    incoming.attach(NavTestLifecycle().lifecycle)
    incoming.runBackStackUpdate { incoming.configureScope {} }
    val shared = fixture.scope.propagationContext.traceId
    val privateContext = PropagationContext()

    navScopes.configureScope { scope ->
      scope.propagationContext = privateContext
      assertThat(scope.propagationContext.traceId).isEqualTo(privateContext.traceId)
      scope.withPropagationContext { assertThat(it.traceId).isEqualTo(privateContext.traceId) }
    }

    assertThat(fixture.scope.propagationContext.traceId).isEqualTo(shared)
  }

  @Test
  fun `propagation changes are published if navigation lease is held`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    navScopes.attach(NavTestLifecycle().lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope {} }
    val context = PropagationContext()

    navScopes.configureScope { scope ->
      scope.withPropagationContext { scope.propagationContext = context }
    }

    assertThat(fixture.scope.propagationContext.traceId).isEqualTo(context.traceId)
  }

  @Test
  fun `navigation lease is not required for unrelated scope writes`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    navScopes.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope { it.screen = "Home" } }
    val incoming = fixture.navScopes()
    incoming.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    incoming.runBackStackUpdate { incoming.configureScope { it.screen = "Home" } }

    assertThat(fixture.scope.screen).isEqualTo("Home")
    val transaction =
      navScopes.startTransaction(TransactionContext("Home", "navigation"), TransactionOptions())
    assertThat(transaction.isNoOp).isTrue()

    navScopes.configureScope(ScopeType.ISOLATION) {
      it.setTag("host", "value")
      it.setContexts("host", mapOf("id" to 42))
    }

    assertThat(fixture.scopes.isolationScope).isNotSameInstanceAs(fixture.scope)
    assertThat(fixture.scopes.isolationScope.tags["host"]).isEqualTo("value")
    assertThat(fixture.scopes.isolationScope.contexts["host"]).isEqualTo(mapOf("id" to 42))
  }

  @Test
  fun `unattached instance can make unrelated scope writes`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()

    navScopes.configureScope(ScopeType.ISOLATION) {
      it.setTag("host", "value")
      it.setContexts("host", mapOf("id" to 42))
    }

    assertThat(fixture.scopes.isolationScope).isNotSameInstanceAs(fixture.scope)
    assertThat(fixture.scopes.isolationScope.tags["host"]).isEqualTo("value")
    assertThat(fixture.scopes.isolationScope.contexts["host"]).isEqualTo(mapOf("id" to 42))
  }

  @Test
  fun `disposing paused previous owner preserves current lease`() {
    val fixture = NavTestFixture()
    val firstNavScopesInstance = fixture.navScopes()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)
    firstNavScopesInstance.attach(owner.lifecycle)
    firstNavScopesInstance.runBackStackUpdate { firstNavScopesInstance.configureScope {} }

    val secondNavScopesInstance = fixture.navScopes()
    secondNavScopesInstance.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    secondNavScopesInstance.runBackStackUpdate { secondNavScopesInstance.configureScope {} }

    owner.pause()
    firstNavScopesInstance.dispose()

    secondNavScopesInstance.configureScope { it.screen = "second" }
    firstNavScopesInstance.configureScope { it.screen = "stale" }
    assertThat(fixture.scope.screen).isEqualTo("second")
  }

  @Test
  fun `a disposed navScopes instance ignores later lifecycle events and does not re-acquire the lease`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)

    navScopes.attach(owner.lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope { it.screen = "Home" } }
    assertThat(fixture.scope.screen).isEqualTo("Home")

    owner.pause()
    navScopes.dispose()
    fixture.scope.screen = "host"

    owner.resume()

    assertThat(fixture.scope.screen).isEqualTo("host")
    assertThat(
        navScopes
          .startTransaction(TransactionContext("Home", "navigation"), TransactionOptions())
          .isNoOp
      )
      .isTrue()
  }

  @Test
  fun `a destroyed lifecycle cannot publish navigation data`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    val owner = NavTestLifecycle(Lifecycle.State.DESTROYED)
    val publishedBackStack = mapOf("Back Stack" to listOf(mapOf("entry" to "/Host")))
    fixture.scope.screen = "Host"
    fixture.scope.setContexts(NAVIGATION_CONTEXT_KEY, publishedBackStack)
    fixture.scopes.addBreadcrumb(Breadcrumb().apply { message = "Host" })

    navScopes.configureScope {
      it.screen = "Home"
      it.setContexts(
        NAVIGATION_CONTEXT_KEY,
        mapOf("Back Stack" to listOf(mapOf("entry" to "/Home"))),
      )
    }
    navScopes.addBreadcrumb(Breadcrumb().apply { message = "Home" })

    navScopes.attach(owner.lifecycle)

    navScopes.configureScope {
      it.screen = "Profile"
      it.setContexts(
        NAVIGATION_CONTEXT_KEY,
        mapOf("Back Stack" to listOf(mapOf("entry" to "/Profile"))),
      )
    }
    navScopes.addBreadcrumb(Breadcrumb().apply { message = "Profile" }, Hint())

    assertThat(fixture.scope.screen).isEqualTo("Host")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isEqualTo(publishedBackStack)
    assertThat(fixture.scope.breadcrumbs.map { it.message }).containsExactly("Host")
  }

  @Test
  fun `a destroyed lifecycle cannot start navigation transactions`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()

    navScopes.attach(NavTestLifecycle(Lifecycle.State.DESTROYED).lifecycle)

    assertThat(
        navScopes
          .startTransaction(TransactionContext("Home", "navigation"), TransactionOptions())
          .isNoOp
      )
      .isTrue()
  }

  @Test
  fun `attaching the same lifecycle twice does not republish navigation data`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)

    navScopes.configureScope {
      it.screen = "Home"
      it.setContexts(
        NAVIGATION_CONTEXT_KEY,
        mapOf("Back Stack" to listOf(mapOf("entry" to "/Home"))),
      )
    }
    navScopes.addBreadcrumb(Breadcrumb().apply { message = "Home" })
    navScopes.attach(owner.lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope {} }
    assertThat(fixture.scope.breadcrumbs.map { it.message }).containsExactly("Home")

    val hostBackStack = mapOf("Back Stack" to listOf(mapOf("entry" to "/Host")))
    fixture.scope.screen = "host override"
    fixture.scope.setContexts(NAVIGATION_CONTEXT_KEY, hostBackStack)

    navScopes.attach(owner.lifecycle)

    assertThat(fixture.scope.screen).isEqualTo("host override")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isEqualTo(hostBackStack)
    assertThat(fixture.scope.breadcrumbs.map { it.message }).containsExactly("Home")
  }

  @Test
  fun `attaching the same lifecycle twice does not start a new navigation transaction`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)

    navScopes.attach(owner.lifecycle)
    navScopes.runBackStackUpdate { navScopes.configureScope {} }
    val transaction =
      navScopes.startTransaction(TransactionContext("Home", "navigation"), TransactionOptions())
    assertThat(transaction.isNoOp).isFalse()
    navScopes.configureScope { it.transaction = transaction }

    navScopes.attach(owner.lifecycle)

    assertThat(fixture.scope.transaction).isSameInstanceAs(transaction)
    assertThat(transaction.isFinished).isFalse()
  }

  @Test
  fun `trying to attach multiple lifecycles throws`() {
    val fixture = NavTestFixture()
    val navScopes = fixture.navScopes()

    navScopes.attach(NavTestLifecycle().lifecycle)

    val exception =
      try {
        navScopes.attach(NavTestLifecycle().lifecycle)
        null
      } catch (e: IllegalStateException) {
        e
      }
    assertThat(exception).isInstanceOf(IllegalStateException::class.java)
  }
}
