package io.sentry.compose.navigation3

import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavSessionTransactionRetentionTest {

  @Test
  fun `restarting a stopped session makes its unfinished transaction available for fallback`() {
    val fixture = NavTestFixture()
    val outgoing = fixture.session()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)
    outgoing.attach(owner.lifecycle)
    outgoing.onBackStackChanged(listOf("Home"))
    val transaction = requireNotNull(fixture.scope.transaction)
    owner.pause()
    val incoming = fixture.session()
    incoming.attach(NavTestLifecycle(Lifecycle.State.CREATED).lifecycle)
    incoming.onBackStackChanged(listOf("Settings"))
    owner.stop()

    owner.start()

    assertThat(fixture.scope.screen).isEqualTo("/Settings")
    incoming.dispose()
    assertThat(transaction.isFinished).isFalse()
    assertThat(fixture.scope.transaction).isSameInstanceAs(transaction)
    assertThat(fixture.scope.screen).isEqualTo("/Home")
  }

  @Test
  fun `returning after stop restores the original transaction until the next navigation change`() {
    val fixture = NavTestFixture()
    val outgoing = fixture.session()
    val outgoingOwner = NavTestLifecycle(Lifecycle.State.RESUMED)
    outgoing.attach(outgoingOwner.lifecycle)
    outgoing.onBackStackChanged(listOf("Home"))
    val transaction = requireNotNull(fixture.scope.transaction)
    outgoingOwner.pause()
    val incoming = fixture.session()
    val incomingOwner = NavTestLifecycle(Lifecycle.State.CREATED)
    incoming.attach(incomingOwner.lifecycle)
    incoming.onBackStackChanged(listOf("Settings"))
    incomingOwner.resume()

    outgoingOwner.stop()

    assertThat(transaction.isFinished).isFalse()
    incoming.dispose()
    assertThat(fixture.scope.transaction).isNull()
    assertThat(fixture.scope.screen).isNull()

    outgoingOwner.resume()

    assertThat(fixture.scope.screen).isEqualTo("/Home")
    assertThat(fixture.scope.transaction).isSameInstanceAs(transaction)
    val child = requireNotNull(fixture.scopes.span).startChild("return work")
    assertThat(child.isNoOp).isFalse()
    assertThat(child.spanContext.parentSpanId).isEqualTo(transaction.spanContext.spanId)
    child.finish()
    assertThat(fixture.scope.breadcrumbs.map { it.data["to"] })
      .containsExactly("/Home", "/Settings")
      .inOrder()
    outgoing.onBackStackChanged(listOf("Profile"))
    assertThat(transaction.isFinished).isTrue()
    assertThat(fixture.scope.transaction?.name).isEqualTo("/Profile")
  }

  @Test
  fun `back stack changes while stopped finish the retained transaction without creating a replacement`() {
    val fixture = NavTestFixture()
    val outgoing = fixture.session()
    val owner = NavTestLifecycle(Lifecycle.State.RESUMED)
    outgoing.attach(owner.lifecycle)
    outgoing.onBackStackChanged(listOf("Home"))
    val transaction = requireNotNull(fixture.scope.transaction)
    val incoming = fixture.session()
    incoming.attach(NavTestLifecycle(Lifecycle.State.RESUMED).lifecycle)
    incoming.onBackStackChanged(listOf("Settings"))
    owner.stop()

    outgoing.onBackStackChanged(listOf("Profile"))

    assertThat(transaction.isFinished).isTrue()
    assertThat(fixture.scope.transaction?.name).isEqualTo("/Settings")
    incoming.dispose()
    owner.resume()
    assertThat(fixture.scope.screen).isEqualTo("/Profile")
    assertThat(fixture.scope.transaction).isNull()
  }
}
