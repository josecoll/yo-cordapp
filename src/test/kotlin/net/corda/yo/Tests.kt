package net.corda.yo

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.DUMMY_PROGRAM_ID
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.getOrThrow
import net.corda.core.identity.AbstractParty
import net.corda.core.node.services.queryBy
import net.corda.yo.Yo.State.YoSchemaV1.YoEntity
import net.corda.core.node.services.vault.QueryCriteria.VaultCustomQueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.utilities.ALICE
import net.corda.core.utilities.BOB
import net.corda.node.utilities.transaction
import net.corda.testing.ALICE_PUBKEY
import net.corda.testing.MINI_CORP_PUBKEY
import net.corda.testing.ledger
import net.corda.testing.node.MockNetwork
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals


class YoTests {
    lateinit var net: MockNetwork
    lateinit var a: MockNetwork.MockNode
    lateinit var b: MockNetwork.MockNode

    @Before
    fun setup() {
        net = MockNetwork()
        val nodes = net.createSomeNodes(2)
        a = nodes.partyNodes[0]
        b = nodes.partyNodes[1]
        net.runNetwork()
    }

    @After
    fun tearDown() {
        net.stopNodes()
    }

    @Test
    fun yoTransactionMustBeWellFormed() {
        // A pre-made Yo to Bob.
        val yo = Yo.State(ALICE, BOB)
        // A pre-made dummy state.
        val dummyState = object : ContractState {
            override val contract get() = DUMMY_PROGRAM_ID
            override val participants: List<AbstractParty> get() = listOf()
        }
        // A pre-made dummy command.
        class DummyCommand : TypeOnlyCommandData()
        // Tests.
        ledger {
            // input state present.
            transaction {
                input { dummyState }
                command(ALICE_PUBKEY) { Yo.Send() }
                output { yo }
                this.failsWith("There can be no inputs when Yo'ing other parties.")
            }
            // No command.
            transaction {
                output { yo }
                this.failsWith("")
            }
            // Wrong command.
            transaction {
                output { yo }
                command(ALICE_PUBKEY) { DummyCommand() }
                this.failsWith("")
            }
            // Command signed by wrong key.
            transaction {
                output { yo }
                command(MINI_CORP_PUBKEY) { Yo.Send() }
                this.failsWith("The Yo! must be signed by the sender.")
            }
            // Sending to yourself is not allowed.
            transaction {
                output { Yo.State(ALICE, ALICE) }
                command(ALICE_PUBKEY) { Yo.Send() }
                this.failsWith("No sending Yo's to yourself!")
            }
            transaction {
                output { yo }
                command(ALICE_PUBKEY) { Yo.Send() }
                this.verifies()
            }
        }
    }

    @Test
    fun flowWorksCorrectly() {
        val yo = Yo.State(a.info.legalIdentity, b.info.legalIdentity)
        val flow = YoFlow(b.info.legalIdentity)
        val future = a.services.startFlow(flow).resultFuture
        net.runNetwork()
        val stx = future.getOrThrow()
        // Check yo transaction is stored in the storage service and the state in the vault.
        val bTx = b.storage.validatedTransactions.getTransaction(stx.id)
        assertEquals(bTx, stx)
        print("bTx == $stx\n")
        b.database.transaction {
            // simple query
            val bYo = b.vaultQuery.queryBy<Yo.State>().states.single().state.data
            assertEquals(bYo.toString(), yo.toString())
            print("$bYo == $yo\n")
            // Using custom criteria directly referencing schema entity attribute
            val expression = builder { YoEntity::yo.equal("Yo!") }
            val customQuery = VaultCustomQueryCriteria(expression)
            val bYo2 = b.vaultQuery.queryBy<Yo.State>(customQuery).states.single().state.data
            assertEquals(bYo2.yo, yo.yo)
            print("$bYo2 == $yo\n")
        }
    }
}
