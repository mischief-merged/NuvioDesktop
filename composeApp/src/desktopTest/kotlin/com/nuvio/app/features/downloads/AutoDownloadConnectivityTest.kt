package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkCondition
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoDownloadConnectivityTest {
    private suspend fun returnsIn(vararg conditions: NetworkCondition) = flowOf(*conditions).internetReturns().toList().size

    @Test
    fun the_internet_returning_after_it_was_gone_is_reported_once() = runTest {
        assertEquals(1, returnsIn(NetworkCondition.Online, NetworkCondition.NoInternet, NetworkCondition.Checking, NetworkCondition.Online, NetworkCondition.Online))
    }

    @Test
    fun the_first_time_the_app_finds_itself_online_is_not_a_return() = runTest {
        assertEquals(0, returnsIn(NetworkCondition.Unknown, NetworkCondition.Checking, NetworkCondition.Online))
    }

    @Test
    fun an_internet_that_is_back_while_the_app_servers_are_not_counts_as_a_return() = runTest {
        assertEquals(1, returnsIn(NetworkCondition.NoInternet, NetworkCondition.ServersUnreachable))
    }

    @Test
    fun every_outage_that_ends_is_reported() = runTest {
        assertEquals(
            2,
            returnsIn(NetworkCondition.NoInternet, NetworkCondition.Online, NetworkCondition.NoInternet, NetworkCondition.NoInternet, NetworkCondition.Online),
        )
    }

    @Test
    fun a_return_is_reported_at_the_moment_the_condition_changes() = runTest {
        val conditions = MutableSharedFlow<NetworkCondition>()
        var returns = 0
        val watching = launch(UnconfinedTestDispatcher(testScheduler)) { conditions.internetReturns().collect { returns++ } }

        conditions.emit(NetworkCondition.NoInternet)
        assertEquals(0, returns)
        conditions.emit(NetworkCondition.Online)
        assertEquals(1, returns)
        watching.cancel()
    }
}
