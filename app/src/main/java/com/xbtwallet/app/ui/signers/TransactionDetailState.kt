package com.xbtwallet.app.ui.signers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xbtwallet.app.chain.TransactionDetail
import com.xbtwallet.app.chain.TransactionRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where the detail screen's one transaction fetch is. */
enum class DetailPhase { LOADING, READY, ERROR }

/** Where the on-demand fee fetch is. IDLE means the fee is either known or not requested. */
enum class FeePhase { IDLE, LOADING, READY, ERROR }

/**
 * The transaction detail screen's state machine, exercised without Compose.
 *
 * The screen opens with the row's summary — direction, net amount, confirmation
 * badge — already in hand, so [row] is rendered from the first frame while the
 * full transaction is fetched. [phase] describes only the parse section: it
 * starts [DetailPhase.LOADING], lands [DetailPhase.READY] with a [detail], or
 * [DetailPhase.ERROR] with a sentence a user can act on. A failure never blanks
 * the header: the row is still the row the list showed.
 *
 * **Fee on demand.** [feePhase] stays [FeePhase.IDLE] until the user taps "Load
 * fee"; the load is all-or-nothing — a parent that cannot be fetched leaves the
 * previous [detail] untouched and shows an error with a Retry. [feeProgress] is
 * the pair the screen prints as "Fetching N parent transactions…".
 *
 * **Cancellation.** Both loads run on [scope], which the screen owns and cancels
 * when the screen leaves composition. A cancelled load leaves no error behind;
 * it simply stops.
 *
 * The two suspend lambdas are injected so the rules above are pinned on the JVM
 * without a socket, an Android framework or a Compose runtime: the screen wires
 * them to [SignerChainState.withChainSession] and the pure chain functions.
 */
@Stable
class TransactionDetailState(
    val txHash: String,
    /** The snapshot row this screen was opened from, or null in the fallback case. */
    val row: TransactionRow?,
    private val scope: CoroutineScope,
    private val loader: suspend () -> TransactionDetail,
    private val feeResolver: suspend (TransactionDetail, (Int, Int) -> Unit) -> TransactionDetail,
) {

    var phase by mutableStateOf(DetailPhase.LOADING)
        private set

    var detail by mutableStateOf<TransactionDetail?>(null)
        private set

    /** A short human sentence for [DetailPhase.ERROR]. */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var feePhase by mutableStateOf(FeePhase.IDLE)
        private set

    /** A short human sentence for [FeePhase.ERROR]. */
    var feeErrorMessage by mutableStateOf<String?>(null)
        private set

    /** Parents fetched so far and the total the fee load needs. */
    var feeProgress by mutableStateOf(0 to 0)
        private set

    private var loadJob: Job? = null
    private var feeJob: Job? = null

    /** The net amount the header shows: the row's when present, else the fetched detail's. */
    val netSatoshis: Long? get() = row?.netSatoshis ?: detail?.netSatoshis

    /** The confirmation count the header badge shows: the row's when present. */
    val confirmations: Int? get() = row?.confirmations ?: detail?.confirmations

    /**
     * Fetches the transaction. Called when the screen appears and by Retry.
     *
     * A second call while the first is still running does nothing — a
     * recomposition must not stack a second fetch on top of the first. After a
     * failure the job is no longer active, so Retry really does retry.
     */
    fun load() {
        if (loadJob?.isActive == true) return
        phase = DetailPhase.LOADING
        errorMessage = null
        loadJob = scope.launch {
            try {
                detail = loader()
                phase = DetailPhase.READY
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                phase = DetailPhase.ERROR
                errorMessage = TransactionDetailStrings.LOAD_FAILED
            }
        }
    }

    /** Retry after a failed load. */
    fun retry() = load()

    /**
     * Fetches the missing parent transactions and fills in the fee. Does nothing
     * when the fee is already known, when a load is already running, or when the
     * detail itself has not loaded yet.
     *
     * The previous detail is kept on failure — a fee load must not take the
     * transaction structure away to report that a parent was unreachable.
     */
    fun loadFee() {
        val current = detail ?: return
        if (current.feeSatoshis != null) return
        if (feeJob?.isActive == true) return
        feePhase = FeePhase.LOADING
        feeErrorMessage = null
        feeProgress = 0 to missingParentCount(current)
        feeJob = scope.launch {
            try {
                detail = feeResolver(current) { fetched, total ->
                    feeProgress = fetched to total
                }
                feePhase = FeePhase.READY
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Partial results are discarded: `detail` still holds the value
                // it had before this load started.
                feePhase = FeePhase.ERROR
                feeErrorMessage = TransactionDetailStrings.FEE_LOAD_FAILED
            }
        }
    }

    /** Retry after a failed fee load. */
    fun retryFee() {
        feePhase = FeePhase.IDLE
        feeErrorMessage = null
        loadFee()
    }
}
