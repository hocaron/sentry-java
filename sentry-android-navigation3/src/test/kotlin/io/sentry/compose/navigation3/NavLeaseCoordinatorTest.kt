package io.sentry.compose.navigation3

import com.google.common.truth.Truth.assertThat
import io.sentry.SpanStatus
import org.junit.Test

class NavLeaseCoordinatorTest {

  @Test
  fun `registration and resume do not preempt an owner before the first prepared update`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    fixture.activate(first)
    val second = fixture.register(isResumed = false)

    fixture.coordinator.resumeCandidate(second, fixture.scope)?.invoke()

    assertThat(fixture.coordinator.hasLease(first)).isTrue()
    assertThat(fixture.coordinator.hasLease(second)).isFalse()
    assertThat(fixture.publications).containsExactly(first)
  }

  @Test
  fun `activation preempts current owner and publishes only after ownership changes`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    val second = fixture.register(isResumed = false)

    fixture.activate(first)
    fixture.activate(first)
    fixture.activate(second)
    fixture.activate(first)

    assertThat(fixture.coordinator.hasLease(second)).isTrue()
    assertThat(fixture.publications).containsExactly(first, second).inOrder()
  }

  @Test
  fun `unregistering current owner restores the most recently resumed eligible candidate`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    val second = fixture.register(isResumed = true)
    val incoming = fixture.register(isResumed = false)
    fixture.activate(first)
    fixture.activate(second)
    fixture.coordinator.resumeCandidate(first, fixture.scope)?.invoke()
    fixture.activate(incoming)

    fixture.coordinator.unregisterCandidate(incoming, fixture.scope)?.invoke()

    assertThat(fixture.coordinator.hasLease(first)).isTrue()
    assertThat(fixture.publications)
      .containsExactly(first, second, first, incoming, first)
      .inOrder()
  }

  @Test
  fun `stopped candidates are not restored on disposal`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    val incoming = fixture.register(isResumed = false)
    fixture.activate(first)
    fixture.activate(incoming)
    fixture.coordinator.stopCandidate(first, fixture.scope)?.invoke()

    fixture.coordinator.unregisterCandidate(incoming, fixture.scope)?.invoke()

    assertThat(fixture.coordinator.hasLease(first)).isFalse()
    assertThat(fixture.coordinator.hasLease(incoming)).isFalse()
  }

  @Test
  fun `first update while paused waits for resume to acquire the lease`() {
    val fixture = Fixture()
    val owner = fixture.register(isResumed = true)
    fixture.coordinator.pauseCandidate(owner, fixture.scope)?.invoke()

    fixture.activate(owner)

    assertThat(fixture.coordinator.hasLease(owner)).isFalse()
    assertThat(fixture.publications).isEmpty()

    fixture.coordinator.resumeCandidate(owner, fixture.scope)?.invoke()

    assertThat(fixture.coordinator.hasLease(owner)).isTrue()
    assertThat(fixture.publications).containsExactly(owner)
  }

  @Test
  fun `stopping a restored paused candidate releases its lease`() {
    val fixture = Fixture()
    val outgoing = fixture.register(isResumed = true)
    fixture.activate(outgoing)
    fixture.coordinator.pauseCandidate(outgoing, fixture.scope)?.invoke()
    val incoming = fixture.register(isResumed = false)
    fixture.activate(incoming)
    fixture.coordinator.unregisterCandidate(incoming, fixture.scope)?.invoke()
    assertThat(fixture.coordinator.hasLease(outgoing)).isTrue()

    fixture.coordinator.stopCandidate(outgoing, fixture.scope)?.invoke()

    assertThat(fixture.coordinator.hasLease(outgoing)).isFalse()
    assertThat(fixture.coordinator.hasLease(incoming)).isFalse()
  }

  @Test
  fun `restoration picks the most recently resumed visible candidate and skips stopped candidates`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    fixture.activate(first)
    fixture.coordinator.pauseCandidate(first, fixture.scope)?.invoke()
    val second = fixture.register(isResumed = true)
    fixture.activate(second)
    fixture.coordinator.pauseCandidate(second, fixture.scope)?.invoke()
    fixture.coordinator.stopCandidate(second, fixture.scope)?.invoke()
    val incoming = fixture.register(isResumed = false)
    fixture.activate(incoming)

    fixture.coordinator.unregisterCandidate(incoming, fixture.scope)?.invoke()

    assertThat(fixture.coordinator.hasLease(first)).isTrue()
    assertThat(fixture.coordinator.hasLease(second)).isFalse()
  }

  @Test
  fun `owners are distinguished by identity even when structurally equal`() {
    val fixture = Fixture()
    val first = listOf("same")
    val second = listOf("same")
    fixture.coordinator.registerCandidate(first, isResumed = false) {}
    fixture.activate(first)

    fixture.coordinator.unregisterCandidate(second, fixture.scope)
    fixture.coordinator.bindTransaction(second, fixture.sdk.transaction())

    assertThat(fixture.coordinator.hasLease(first)).isTrue()
    assertThat(fixture.coordinator.hasLease(second)).isFalse()
  }

  @Test
  fun `handoff detaches transaction and disposal restores it before publishing`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    fixture.activate(first)
    val tx = fixture.bind(first)
    tx.status = SpanStatus.CANCELLED
    val incoming = fixture.register(isResumed = false)

    fixture.activate(incoming)

    assertThat(tx.isFinished).isFalse()
    assertThat(tx.status).isEqualTo(SpanStatus.CANCELLED)
    assertThat(fixture.scope.transaction).isNull()

    fixture.coordinator.unregisterCandidate(incoming, fixture.scope)?.invoke()

    assertThat(fixture.coordinator.hasLease(first)).isTrue()
    assertThat(tx.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(tx)
  }

  @Test
  fun `stopping a displaced owner retains its transaction without restoring its lease`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    fixture.activate(first)
    val tx = fixture.bind(first)
    val incoming = fixture.register(isResumed = false)
    fixture.activate(incoming)

    fixture.coordinator.stopCandidate(first, fixture.scope)?.invoke()
    fixture.coordinator.unregisterCandidate(incoming, fixture.scope)?.invoke()

    assertThat(tx.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isNull()
    assertThat(fixture.coordinator.hasLease(first)).isFalse()

    fixture.coordinator.resumeCandidate(first, fixture.scope)?.invoke()

    assertThat(fixture.scope.transaction).isSameInstanceAs(tx)
  }

  @Test
  fun `handoff after the outgoing owner stopped retains its transaction for return`() {
    val fixture = Fixture()
    val outgoing = fixture.register(isResumed = true)
    fixture.activate(outgoing)
    val transaction = fixture.bind(outgoing)
    fixture.coordinator.stopCandidate(outgoing, fixture.scope)?.invoke()
    val incoming = fixture.register(isResumed = false)

    fixture.activate(incoming)

    assertThat(transaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isNull()

    fixture.coordinator.unregisterCandidate(incoming, fixture.scope)?.invoke()
    fixture.coordinator.resumeCandidate(outgoing, fixture.scope)?.invoke()

    assertThat(fixture.scope.transaction).isSameInstanceAs(transaction)
  }

  @Test
  fun `pause without a replacement retains existing transaction but releases authority`() {
    val fixture = Fixture()
    val owner = fixture.register(isResumed = true)
    fixture.activate(owner)
    val tx = fixture.bind(owner)

    fixture.coordinator.pauseCandidate(owner, fixture.scope)?.invoke()

    assertThat(fixture.coordinator.hasLease(owner)).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(tx)
    assertThat(tx.isFinished).isFalse()

    fixture.coordinator.resumeCandidate(owner, fixture.scope)?.invoke()

    assertThat(fixture.coordinator.hasLease(owner)).isTrue()
    assertThat(fixture.scope.transaction).isSameInstanceAs(tx)
  }

  @Test
  fun `incoming update detaches the retained transaction of an already paused owner`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    fixture.activate(first)
    val tx = fixture.bind(first)
    fixture.coordinator.pauseCandidate(first, fixture.scope)?.invoke()

    fixture.activate(fixture.register(isResumed = false))

    assertThat(tx.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isNull()
  }

  @Test
  fun `pausing current owner restores both authority and the previous transaction`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    fixture.activate(first)
    val firstTx = fixture.bind(first)
    val second = fixture.register(isResumed = true)
    fixture.activate(second)
    val secondTx = fixture.bind(second)

    fixture.coordinator.pauseCandidate(second, fixture.scope)?.invoke()

    assertThat(secondTx.isFinished).isFalse()
    assertThat(firstTx.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(firstTx)
    assertThat(fixture.coordinator.hasLease(first)).isTrue()
    assertThat(fixture.publications).containsExactly(first, second, first).inOrder()
  }

  @Test
  fun `resuming displaced owner swaps transactions and allows rollback`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    fixture.activate(first)
    val firstTx = fixture.bind(first)
    val second = fixture.register(isResumed = true)
    fixture.activate(second)
    val secondTx = fixture.bind(second)

    fixture.coordinator.resumeCandidate(first, fixture.scope)?.invoke()

    assertThat(fixture.scope.transaction).isSameInstanceAs(firstTx)
    assertThat(secondTx.isFinished).isFalse()
    assertThat(fixture.coordinator.hasLease(first)).isTrue()

    fixture.coordinator.unregisterCandidate(first, fixture.scope)?.invoke()

    assertThat(firstTx.isFinished).isTrue()
    assertThat(secondTx.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(secondTx)
    assertThat(fixture.coordinator.hasLease(second)).isTrue()
  }

  @Test
  fun `host transaction is preserved during handoff restoration and disposal`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    fixture.activate(first)
    val navTx = fixture.bind(first)
    val hostTx = fixture.sdk.transaction("host")
    fixture.scope.transaction = hostTx
    val second = fixture.register(isResumed = false)

    fixture.activate(second)
    fixture.coordinator.stopCandidate(first, fixture.scope)?.invoke()
    fixture.coordinator.unregisterCandidate(second, fixture.scope)?.invoke()
    fixture.coordinator.resumeCandidate(first, fixture.scope)?.invoke()

    assertThat(fixture.coordinator.hasLease(first)).isTrue()
    assertThat(fixture.scope.transaction).isSameInstanceAs(hostTx)
    assertThat(navTx.isFinished).isFalse()

    fixture.coordinator.unregisterCandidate(first, fixture.scope)?.invoke()

    assertThat(navTx.isFinished).isTrue()
    assertThat(hostTx.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(hostTx)
  }

  @Test
  fun `transaction finished while its owner is stopped is discarded on return`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    fixture.activate(first)
    val tx = fixture.bind(first)
    val second = fixture.register(isResumed = false)
    fixture.activate(second)
    fixture.coordinator.stopCandidate(first, fixture.scope)?.invoke()
    tx.finish()

    fixture.coordinator.unregisterCandidate(second, fixture.scope)?.invoke()
    fixture.coordinator.resumeCandidate(first, fixture.scope)?.invoke()

    assertThat(fixture.coordinator.hasLease(first)).isTrue()
    assertThat(fixture.scope.transaction).isNull()
  }

  @Test
  fun `disposing a former owner leaves current authority and transaction intact`() {
    val fixture = Fixture()
    val first = fixture.register(isResumed = true)
    fixture.activate(first)
    val firstTx = fixture.bind(first)
    val second = fixture.register(isResumed = false)
    fixture.activate(second)
    val secondTx = fixture.bind(second)
    fixture.coordinator.stopCandidate(first, fixture.scope)?.invoke()

    assertThat(firstTx.isFinished).isFalse()

    fixture.coordinator.unregisterCandidate(first, fixture.scope)?.invoke()

    assertThat(firstTx.isFinished).isTrue()
    assertThat(secondTx.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(secondTx)
    assertThat(fixture.coordinator.hasLease(second)).isTrue()
  }

  private class Fixture {
    val sdk = NavTestFixture()
    val coordinator = sdk.coordinator
    val scope = sdk.scope
    val publications = mutableListOf<Any>()

    fun register(isResumed: Boolean): Any =
      Any().also { owner ->
        coordinator.registerCandidate(owner, isResumed) {
          assertThat(coordinator.hasLease(owner)).isTrue()
          publications += owner
        }
      }

    fun activate(owner: Any) {
      coordinator.activateCandidate(owner, scope)?.invoke()
    }

    fun bind(owner: Any) =
      sdk.transaction().also {
        coordinator.bindTransaction(owner, it)
        scope.transaction = it
      }
  }
}
