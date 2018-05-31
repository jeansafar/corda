package net.corda.node.services

import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.StateMachineRunId
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.finance.POUNDS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.issuedBy
import net.corda.node.internal.StartedNode
import net.corda.node.services.statemachine.StaffedFlowHospital
import net.corda.node.services.statemachine.StaffedFlowHospital.MedicalHistory.Record.Observation
import net.corda.node.services.statemachine.StateMachineManager.Change
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNodeParameters
import net.corda.testing.node.internal.startFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import java.util.*
import kotlin.collections.ArrayList

class FinalityHandlerTest {
    private lateinit var mockNet: InternalMockNetwork

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `sent to flow hospital on error and attempted retry on node restart`() {
        // Setup a network where only Alice has the finance CorDapp and it sends a cash tx to Bob who doesn't have the
        // CorDapp. Bob's FinalityHandler will error when validating the tx.
        mockNet = InternalMockNetwork(cordappPackages = emptyList())

        val alice = mockNet.createNode(InternalMockNodeParameters(
                legalName = ALICE_NAME,
                extraCordappPackages = listOf("net.corda.finance.contracts.asset")
        ))

        var bob = mockNet.createNode(InternalMockNodeParameters(legalName = BOB_NAME))

        val stx = TransactionBuilder(mockNet.defaultNotaryIdentity).let {
            Cash().generateIssue(
                    it,
                    1000.POUNDS.issuedBy(alice.info.singleIdentity().ref(0)),
                    bob.info.singleIdentity(),
                    mockNet.defaultNotaryIdentity
            )
            alice.services.signInitialTransaction(it)
        }

        val bobFinalityHandlerChanges = bob.finalityHandlerUpdates()

        val finalisedTx = alice.services.startFlow(FinalityFlow(stx)).run {
            mockNet.runNetwork()
            resultFuture.getOrThrow()
        }

        assertThat(bob.getTransaction(finalisedTx.id)).isNull()
        val finalityHandlerId = bobFinalityHandlerChanges.assertFlowNotRemoved()

        val observations = bob.smm.flowHospital.patients[finalityHandlerId]!!.records.filterIsInstance<Observation>()
        assertThat(observations).hasSize(1)
        assertThat(observations[0].by).contains(StaffedFlowHospital.FinalityDoctor)

        bob = mockNet.restartNode(bob)
        Thread.sleep(500)

        assertThat(bob.getTransaction(finalisedTx.id)).isNull()
        // Make sure the finality handler is still in the SMM on restart. Because we've not fixed its error we expect
        // it to be still there after a small execution delay.
        assertThat(bob.smm.allStateMachines.map { it.runId }).contains(finalityHandlerId)
    }

    private fun StartedNode<*>.getTransaction(id: SecureHash): SignedTransaction? {
        return database.transaction {
            services.validatedTransactions.getTransaction(id)
        }
    }

    private fun StartedNode<*>.finalityHandlerUpdates(): List<Change> {
        val finalityHandlerChanges = Collections.synchronizedList(ArrayList<Change>())
        smm.track()
                .updates
                .filter { it.logic is FinalityHandler }
                .subscribe { finalityHandlerChanges += it }
        return finalityHandlerChanges
    }

    private fun List<Change>.assertFlowNotRemoved(): StateMachineRunId {
        assertThat(filterIsInstance<Change.Removed>()).isEmpty()
        assertThat(filterIsInstance<Change.Add>()).hasSize(1)
        return this[0].logic.runId
    }
}
