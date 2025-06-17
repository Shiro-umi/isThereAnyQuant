import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// vm entry
fun main() {
//    runBlocking {
//        val outer = SingleStepIterator()
//        val outerList = List(3) {
//            suspend {
//                println("$YELLOW[WARNING] outer $it $RESET")
//                val inner = SingleStepIterator()
//                val innerList = List(3) {
//                    suspend {
//                        println("$YELLOW[WARNING]           inner $it $RESET")
//                    }
//                }
//                println("$YELLOW[WARNING]           inner ${inner.submitTasks(innerList)} $RESET")
//            }
//        }
//        println("$YELLOW[WARNING] ${outer.submitTasks(outerList)} $RESET")
//    }
}

//
//open class SingleStepIterator : Logger {
//
//    override val className: String = "SingleStepIterator"
//    private var continuation: Continuation<Unit>? = null
//
//    private val taskFlow = MutableSharedFlow<suspend () -> Unit>(replay = Int.MAX_VALUE)
//
//    open suspend fun submitTasks(tasks: List<suspend () -> Unit>): Boolean {
//        return suspendCoroutine<Boolean> { cont ->
//            supervisorScope.launch {
//                tasks.forEach { task -> taskFlow.emit(task) }
//                taskFlow.emit {
//                    cont.resume(nextStep())
//                }
//                taskFlow.collect { task ->
//                    task()
//                    continuation?.let { nextStep() }
//                }
//            }
//
//        }
//    }
//
//    open suspend fun nextStep(): Boolean {
//        val res = continuation == null
//        continuation?.resume(Unit)
//        continuation = null
//        return res
//    }
//}
