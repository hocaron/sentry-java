package io.sentry.compose.navigation3

import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavSessionTest {

  @Test
  fun `abandoned activity launch restores the paused outgoing session before it stops`() {
    val fixture = NavTestFixture()
    val outgoing = fixture.session()
    val outgoingOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    outgoing.attach(outgoingOwner.lifecycle)
    outgoing.onBackStackChanged(listOf("Home"))
    val transaction = requireNotNull(fixture.scope.transaction)
    outgoingOwner.pause()
    val incoming = fixture.session()
    incoming.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    incoming.onBackStackChanged(listOf("Settings"))
    assertThat(transaction.isFinished).isFalse()

    incoming.dispose()

    assertThat(fixture.scope.transaction).isSameInstanceAs(transaction)
    assertThat(fixture.scope.screen).isEqualTo("/Home")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Home"))))
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Settings")
      .inOrder()

    outgoing.onBackStackChanged(listOf("Profile"))

    assertThat(fixture.scope.transaction?.name).isEqualTo("/Profile")
    assertThat(fixture.scope.screen).isEqualTo("/Profile")
  }

  @Test
  fun `the initial destination publishes navigation data before the owner enters a resumed state`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    val owner = NavTestLifecycle(Lifecycle.State.CREATED)
    session.attach(owner.lifecycle)

    session.onBackStackChanged(listOf("Home"))

    val transaction = requireNotNull(fixture.scope.transaction)
    assertThat(transaction.name).isEqualTo("/Home")
    assertThat(transaction.isFinished).isFalse()
    assertThat(fixture.scope.screen).isEqualTo("/Home")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Home"))))
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] }).containsExactly("/Home")
  }

  @Test
  fun `updates after eager publication emit data before the owner resumes`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    val owner = NavTestLifecycle(Lifecycle.State.CREATED)
    session.attach(owner.lifecycle)
    session.onBackStackChanged(listOf("Home"))
    session.onBackStackChanged(listOf("Home", "Profile"))

    assertThat(fixture.scope.screen).isEqualTo("/Profile")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(
        mapOf("Back Stack" to listOf(mapOf("entry" to "/Profile"), mapOf("entry" to "/Home")))
      )
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Profile")
      .inOrder()
    assertThat(fixture.scope.transaction?.name).isEqualTo("/Profile")
  }

  @Test
  fun `changing the visible destination publishes navigation data and replaces the navigation transaction`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    session.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    session.onBackStackChanged(listOf("Home"))
    val previousTransaction = requireNotNull(fixture.scope.transaction)

    session.onBackStackChanged(listOf("Home", "Profile"))

    assertThat(previousTransaction.isFinished).isTrue()
    val currentTransaction = requireNotNull(fixture.scope.transaction)
    assertThat(currentTransaction).isNotSameInstanceAs(previousTransaction)
    assertThat(currentTransaction.name).isEqualTo("/Profile")
    assertThat(currentTransaction.isFinished).isFalse()
    assertThat(fixture.scope.screen).isEqualTo("/Profile")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(
        mapOf("Back Stack" to listOf(mapOf("entry" to "/Profile"), mapOf("entry" to "/Home")))
      )
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Profile")
      .inOrder()
    assertThat(fixture.scope.breadcrumbs.last().data["from"]).isEqualTo("/Home")
  }

  @Test
  fun `incoming session suspends previous txn and replaces navigation data before resume`() {
    val fixture = NavTestFixture()
    val previousSession = fixture.session()
    previousSession.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    previousSession.onBackStackChanged(listOf("Home"))
    val previousTransaction = requireNotNull(fixture.scope.transaction)
    val incomingSession = fixture.session()
    incomingSession.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    assertThat(previousTransaction.isFinished).isFalse()

    incomingSession.onBackStackChanged(listOf("Settings"))

    assertThat(previousTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction?.name).isEqualTo("/Settings")
    assertThat(fixture.scope.screen).isEqualTo("/Settings")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Settings"))))
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Settings")
      .inOrder()
  }

  @Test
  fun `disposing incoming session restores still-resumed previous session data`() {
    val fixture = NavTestFixture()
    val previousSession = fixture.session()
    val previousOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    previousSession.attach(previousOwner.lifecycle)
    previousSession.onBackStackChanged(listOf("Home"))
    val incomingSession = fixture.session()
    incomingSession.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    incomingSession.onBackStackChanged(listOf("Settings"))
    assertThat(fixture.scope.screen).isEqualTo("/Settings")

    incomingSession.dispose()

    assertThat(fixture.scope.screen).isEqualTo("/Home")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Home"))))
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Settings")
      .inOrder()
  }

  @Test
  fun `disposing incoming session restores the previous transaction`() {
    val fixture = NavTestFixture()
    val previousSession = fixture.session()
    previousSession.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    previousSession.onBackStackChanged(listOf("Home"))
    val previousTransaction = requireNotNull(fixture.scope.transaction)
    val incomingSession = fixture.session()
    incomingSession.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    incomingSession.onBackStackChanged(listOf("Settings"))
    val incomingTransaction = requireNotNull(fixture.scope.transaction)

    incomingSession.dispose()

    assertThat(previousTransaction.isFinished).isFalse()
    assertThat(incomingTransaction.isFinished).isTrue()
    assertThat(fixture.scope.transaction).isSameInstanceAs(previousTransaction)

    previousSession.onBackStackChanged(listOf("Home", "Profile"))

    val restoredOwnerTransaction = requireNotNull(fixture.scope.transaction)
    assertThat(previousTransaction.isFinished).isTrue()
    assertThat(restoredOwnerTransaction).isNotSameInstanceAs(previousTransaction)
    assertThat(restoredOwnerTransaction.name).isEqualTo("/Profile")
  }

  @Test
  fun `disposing incoming session does not restore previous session after it stops`() {
    val fixture = NavTestFixture()
    val previousSession = fixture.session()
    val previousOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    previousSession.attach(previousOwner.lifecycle)
    previousSession.onBackStackChanged(listOf("Home"))
    val previousTransaction = requireNotNull(fixture.scope.transaction)
    val incomingSession = fixture.session()
    incomingSession.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    incomingSession.onBackStackChanged(listOf("Settings"))

    previousOwner.stop()
    incomingSession.dispose()

    assertThat(previousTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isNull()
    assertThat(fixture.scope.screen).isNull()
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isNull()
  }

  @Test
  fun `resuming previous session reclaims navigation authority without replaying its breadcrumb`() {
    val fixture = NavTestFixture()
    val previousSession = fixture.session()
    val previousOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    previousSession.attach(previousOwner.lifecycle)
    previousSession.onBackStackChanged(listOf("Home"))
    previousOwner.pause()
    val incomingSession = fixture.session()
    incomingSession.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    incomingSession.onBackStackChanged(listOf("Settings"))

    previousOwner.resume()

    assertThat(fixture.scope.transaction?.name).isEqualTo("/Home")
    assertThat(fixture.scope.screen).isEqualTo("/Home")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Home"))))
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Settings")
      .inOrder()

    previousSession.onBackStackChanged(listOf("Profile"))

    assertThat(fixture.scope.transaction?.name).isEqualTo("/Profile")
    assertThat(fixture.scope.screen).isEqualTo("/Profile")
  }

  @Test
  fun `outgoing session can continue navigating until the incoming update is prepared`() {
    val fixture = NavTestFixture()
    val outgoing = fixture.session()
    outgoing.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    outgoing.onBackStackChanged(listOf("Home"))
    val incoming = fixture.session()
    incoming.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)

    outgoing.onBackStackChanged(listOf("Profile"))

    assertThat(fixture.scope.transaction?.name).isEqualTo("/Profile")
    assertThat(fixture.scope.screen).isEqualTo("/Profile")
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Profile")
      .inOrder()

    incoming.onBackStackChanged(listOf("Settings"))

    assertThat(fixture.scope.transaction?.name).isEqualTo("/Settings")
    assertThat(fixture.scope.screen).isEqualTo("/Settings")
  }

  @Test
  fun `pausing incoming session restores previous transaction data and authority together`() {
    val fixture = NavTestFixture()
    val previous = fixture.session()
    previous.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    previous.onBackStackChanged(listOf("Home"))
    val previousTransaction = requireNotNull(fixture.scope.transaction)
    val incoming = fixture.session()
    val incomingOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    incoming.attach(incomingOwner.lifecycle)
    incoming.onBackStackChanged(listOf("Settings"))
    val incomingTransaction = requireNotNull(fixture.scope.transaction)

    incomingOwner.pause()

    assertThat(incomingTransaction.isFinished).isFalse()
    assertThat(previousTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(previousTransaction)
    assertThat(fixture.scope.screen).isEqualTo("/Home")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Home"))))

    previous.onBackStackChanged(listOf("Profile"))
    incoming.onBackStackChanged(listOf("Hidden"))

    assertThat(fixture.scope.transaction?.name).isEqualTo("/Profile")
    assertThat(fixture.scope.screen).isEqualTo("/Profile")
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Settings", "/Profile")
      .inOrder()
  }

  @Test
  fun `paused session stages navigation without creating a new transaction`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)
    session.attach(owner.lifecycle)
    session.onBackStackChanged(listOf("Home"))
    val transaction = requireNotNull(fixture.scope.transaction)

    owner.pause()

    assertThat(transaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(transaction)

    session.onBackStackChanged(listOf("Profile"))

    assertThat(fixture.scope.transaction).isNull()
    assertThat(fixture.scope.screen).isEqualTo("/Home")
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] }).containsExactly("/Home")

    owner.resume()

    assertThat(fixture.scope.screen).isEqualTo("/Profile")
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] }).containsExactly("/Home")

    session.onBackStackChanged(listOf("Settings"))

    assertThat(fixture.scope.transaction?.name).isEqualTo("/Settings")
    assertThat(fixture.scope.screen).isEqualTo("/Settings")
  }

  @Test
  fun `propagation context follows restored navigation ownership when transactions are disabled`() {
    val fixture = NavTestFixture()
    fixture.options.setTracesSampleRate(null)
    val previous = fixture.session()
    val previousOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    previous.attach(previousOwner.lifecycle)
    previous.onBackStackChanged(listOf("Home"))
    val homeTrace = fixture.scope.propagationContext.traceId
    val incoming = fixture.session()
    incoming.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    incoming.onBackStackChanged(listOf("Settings"))
    val settingsTrace = fixture.scope.propagationContext.traceId
    assertThat(settingsTrace).isNotEqualTo(homeTrace)

    incoming.dispose()

    assertThat(fixture.scope.propagationContext.traceId).isEqualTo(homeTrace)
    assertThat(fixture.scope.screen).isEqualTo("/Home")

    previousOwner.pause()
    previous.onBackStackChanged(listOf("Profile"))

    assertThat(fixture.scope.propagationContext.traceId).isEqualTo(homeTrace)
    assertThat(fixture.scope.screen).isEqualTo("/Home")

    previousOwner.resume()

    assertThat(fixture.scope.propagationContext.traceId).isNotEqualTo(homeTrace)
    assertThat(fixture.scope.propagationContext.traceId).isNotEqualTo(settingsTrace)
    assertThat(fixture.scope.screen).isEqualTo("/Profile")
  }

  @Test
  fun `previous session destination changes do not overwrite the current sessions navigation data and transaction`() {
    val fixture = NavTestFixture()
    val previousSession = fixture.session()
    previousSession.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    previousSession.onBackStackChanged(listOf("Home"))
    val currentSession = fixture.session()
    currentSession.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    currentSession.onBackStackChanged(listOf("Settings"))
    val currentTransaction = requireNotNull(fixture.scope.transaction)

    previousSession.onBackStackChanged(listOf("Profile"))

    assertThat(fixture.scope.screen).isEqualTo("/Settings")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Settings"))))
    assertThat(fixture.scope.transaction).isSameInstanceAs(currentTransaction)
    assertThat(currentTransaction.isFinished).isFalse()
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Settings")
      .inOrder()
  }

  @Test
  fun `disposing the previous session does not modify the current sessions navigation data and transaction`() {
    val fixture = NavTestFixture()
    val previousSession = fixture.session()
    previousSession.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    previousSession.onBackStackChanged(listOf("Home"))
    val currentSession = fixture.session()
    currentSession.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    currentSession.onBackStackChanged(listOf("Settings"))
    val currentTransaction = requireNotNull(fixture.scope.transaction)

    previousSession.dispose()

    assertThat(fixture.scope.screen).isEqualTo("/Settings")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Settings"))))
    assertThat(fixture.scope.transaction).isSameInstanceAs(currentTransaction)
    assertThat(currentTransaction.isFinished).isFalse()
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Settings")
      .inOrder()
  }

  @Test
  fun `disposing an incoming session before its first update lets the previous session continue navigation`() {
    val fixture = NavTestFixture()
    val previousSession = fixture.session()
    previousSession.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    previousSession.onBackStackChanged(listOf("Home"))
    val previousTransaction = requireNotNull(fixture.scope.transaction)
    val incomingSession = fixture.session()
    incomingSession.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)

    incomingSession.dispose()

    assertThat(previousTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(previousTransaction)

    previousSession.onBackStackChanged(listOf("Profile"))

    assertThat(previousTransaction.isFinished).isTrue()
    assertThat(fixture.scope.transaction?.name).isEqualTo("/Profile")
    assertThat(fixture.scope.screen).isEqualTo("/Profile")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Profile"))))
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Profile")
      .inOrder()
  }

  @Test
  fun `an incoming session with an empty back stack suspends the previous transaction`() {
    val fixture = NavTestFixture()
    val previousSession = fixture.session()
    previousSession.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    previousSession.onBackStackChanged(listOf("Home"))
    val previousTransaction = requireNotNull(fixture.scope.transaction)
    val incomingSession = fixture.session()
    incomingSession.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)

    incomingSession.onBackStackChanged(emptyList())

    assertThat(previousTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isNull()
  }

  @Test
  fun `incoming session with transactions disabled suspends previous txn and publishes navigation data`() {
    val fixture = NavTestFixture()
    val previousSession = fixture.session()
    previousSession.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    previousSession.onBackStackChanged(listOf("Home"))
    val previousTransaction = requireNotNull(fixture.scope.transaction)
    val incomingSession = fixture.session(SentryNavOptions { enableNavigationTransactions = false })
    incomingSession.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)

    incomingSession.onBackStackChanged(listOf("Settings"))

    assertThat(previousTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isNull()
    assertThat(fixture.scope.screen).isEqualTo("/Settings")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Settings"))))
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Settings")
      .inOrder()
  }

  @Test
  fun `session updates and disposal preserve an active host transaction`() {
    val fixture = NavTestFixture()
    val hostTransaction = fixture.transaction()
    fixture.scope.transaction = hostTransaction
    val session = fixture.session()
    session.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)

    session.onBackStackChanged(listOf("Home"))

    assertThat(fixture.scope.screen).isEqualTo("/Home")
    assertThat(fixture.scope.transaction).isSameInstanceAs(hostTransaction)

    session.dispose()

    assertThat(hostTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(hostTransaction)
    assertThat(fixture.scope.screen).isNull()
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isNull()
  }

  @Test
  fun `disposing current session clears nav state and finishes its txn while retaining breadcrumbs`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    session.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    session.onBackStackChanged(listOf("Home"))
    val transaction = requireNotNull(fixture.scope.transaction)

    session.dispose()

    assertThat(transaction.isFinished).isTrue()
    assertThat(fixture.scope.transaction).isNull()
    assertThat(fixture.scope.screen).isNull()
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isNull()
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] }).containsExactly("/Home")
  }

  @Test
  fun `disposing an already disposed session preserves navigation state published afterwards`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    session.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    session.onBackStackChanged(listOf("Home"))
    session.dispose()
    val hostBackStack = mapOf("Back Stack" to listOf(mapOf("entry" to "/Host")))
    fixture.scope.screen = "Host"
    fixture.scope.setContexts(NAVIGATION_CONTEXT_KEY, hostBackStack)
    val hostTransaction = fixture.transaction()
    fixture.scope.transaction = hostTransaction

    session.dispose()

    assertThat(fixture.scope.screen).isEqualTo("Host")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isEqualTo(hostBackStack)
    assertThat(fixture.scope.transaction).isSameInstanceAs(hostTransaction)
    assertThat(hostTransaction.isFinished).isFalse()
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] }).containsExactly("/Home")
  }

  @Test
  fun `destroying the owner clears navigation state and finishes its transaction while retaining breadcrumbs`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)
    session.attach(owner.lifecycle)
    session.onBackStackChanged(listOf("Home"))
    val transaction = requireNotNull(fixture.scope.transaction)

    owner.destroy()

    assertThat(transaction.isFinished).isTrue()
    assertThat(fixture.scope.transaction).isNull()
    assertThat(fixture.scope.screen).isNull()
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isNull()
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] }).containsExactly("/Home")
  }

  @Test
  fun `disposing a paused session clears its last published state rather than its staged destination`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)
    session.attach(owner.lifecycle)
    session.onBackStackChanged(listOf("Home"))

    owner.pause()
    session.onBackStackChanged(listOf("Hidden"))

    assertThat(fixture.scope.screen).isEqualTo("/Home")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Home"))))

    session.dispose()

    assertThat(fixture.scope.screen).isNull()
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isNull()
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] }).containsExactly("/Home")
  }

  @Test
  fun `destroying an incoming owner before resume restores the previous sessions state and transaction`() {
    val fixture = NavTestFixture()
    val previous = fixture.session()
    previous.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    previous.onBackStackChanged(listOf("Home"))
    val previousTransaction = requireNotNull(fixture.scope.transaction)
    val incoming = fixture.session()
    val incomingOwner = NavTestLifecycle(Lifecycle.State.CREATED)
    incoming.attach(incomingOwner.lifecycle)
    incoming.onBackStackChanged(listOf("Settings"))
    val incomingTransaction = requireNotNull(fixture.scope.transaction)

    incomingOwner.destroy()
    incoming.dispose()

    assertThat(incomingTransaction.isFinished).isTrue()
    assertThat(previousTransaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(previousTransaction)
    assertThat(fixture.scope.screen).isEqualTo("/Home")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY])
      .isEqualTo(mapOf("Back Stack" to listOf(mapOf("entry" to "/Home"))))
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Settings")
      .inOrder()
  }

  @Test
  fun `destroying an owner before resume clears its published state without a replacement`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    val owner = NavTestLifecycle(Lifecycle.State.CREATED)
    session.attach(owner.lifecycle)
    session.onBackStackChanged(listOf("Home"))

    owner.destroy()

    assertThat(fixture.scope.screen).isNull()
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isNull()
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] }).containsExactly("/Home")
  }

  @Test
  fun `disposing current session preserves navigation context replaced by the host`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    session.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    session.onBackStackChanged(listOf("Home"))
    // Even an equal replacement belongs to the host, not the navigation session.
    val hostContext = mapOf("Back Stack" to listOf(mapOf("entry" to "/Home")))
    fixture.scope.setContexts(NAVIGATION_CONTEXT_KEY, hostContext)

    session.dispose()

    assertThat(fixture.scope.screen).isNull()
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isSameInstanceAs(hostContext)
  }

  @Test
  fun `disposing current session preserves screen replaced by the host`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    session.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    session.onBackStackChanged(listOf("Home"))
    val hostScreen = String(charArrayOf('/', 'H', 'o', 'm', 'e'))
    fixture.scope.screen = hostScreen

    session.dispose()

    assertThat(fixture.scope.screen).isSameInstanceAs(hostScreen)
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isNull()
  }

  @Test
  fun `destroying a paused owner preserves host replacements and later composition disposal is harmless`() {
    val fixture = NavTestFixture()
    val session = fixture.session()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)
    session.attach(owner.lifecycle)
    session.onBackStackChanged(listOf("Home"))
    owner.pause()
    val hostContext = mapOf("route" to "Host")
    fixture.scope.screen = "Host"
    fixture.scope.setContexts(NAVIGATION_CONTEXT_KEY, hostContext)

    owner.destroy()
    session.dispose()

    assertThat(fixture.scope.screen).isEqualTo("Host")
    assertThat(fixture.scope.contexts[NAVIGATION_CONTEXT_KEY]).isSameInstanceAs(hostContext)
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] }).containsExactly("/Home")
  }
}
