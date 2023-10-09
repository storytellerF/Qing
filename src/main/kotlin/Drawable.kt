import com.improve_future.case_changer.toCamelCase
import com.j256.simplemagic.ContentInfoUtil
import java.io.File
import java.nio.file.Path

fun File.drawableName() = name.split(".").first()

fun File.extractImageDimension(
    contentInfoUtil: ContentInfoUtil
) = contentInfoUtil.findMatch(this).message.split(",")[1].split("x").map { dimension ->
    dimension.trim().toInt()
}

abstract class Detector(val module: File) {

    protected val data = mutableMapOf<String, Set<String>>()
    private var initialized = false
    fun detect(): Map<String, Set<String>> {
        if (initialized) return data
        data.putAll(detectInternal().filter {
            it.key.isNotEmpty()
        })
        initialized = true
        return data
    }

    protected abstract fun detectInternal(): Map<String, Set<String>>

    fun run(indexObj: Path, isDry: Boolean) {
        if (!initialized) detect()
        runInternal(indexObj, isDry)
    }

    abstract fun runInternal(indexObj: Path, isDry: Boolean)
}

class DrawableDetector(module: File) : Detector(module) {
    override fun detectInternal() =
        resources(module, { name -> name.startsWith("drawable") }) { it.extension == "png" }

    override fun runInternal(indexObj: Path, isDry: Boolean) {
        val (count, c, space) = deleteUnused(indexObj, data, isDry) {
            listOf(
                it,
                "R.drawable.$it"
            )
        }
        println("drawable total ${data.size} delete $count $c space ${space.toFloat() / 1048576} MB")

    }
}

class LayoutDetector(module: File) : Detector(module) {
    override fun detectInternal() = resources(module, { it.startsWith("layout") }) {
        it.extension == "xml"
    }

    override fun runInternal(indexObj: Path, isDry: Boolean) {
        val (count, c, space) = deleteUnused(indexObj, data, isDry) {
            val listOf = listOf(
                it,
                "R.layout.$it",
                it.toCamelCase() + "Binding",
                it.toCamelCase() + "Binding.bind",
                it.toCamelCase() + "Binding.inflate",
                it.toCamelCase() + "Binding\\:\\:bind",
                it.toCamelCase() + "Binding\\:\\:inflate"
            )
            listOf
        }
        println("layout total ${data.size} delete $count $c space ${space.toFloat() / 1048576} MB")

    }

}

class RawDetector(module: File) : Detector(module) {
    override fun detectInternal() = resources(module, { it.startsWith("raw") }) {
        true
    }

    override fun runInternal(indexObj: Path, isDry: Boolean) {
        val (count, c, space) = deleteUnused(indexObj, data, isDry) {
            listOf(it, "R.raw.$it")
        }
        println("raw total ${data.size} delete $count $c space ${space.toFloat() / 1048576} MB")
    }

}

class ReportDetector(module: File) : Detector(module) {
    override fun detectInternal(): Map<String, Set<String>> {
        val reportRoot = File(module, "build/reports/")
        val reportXmlPath = reportRoot.list { _, name ->
            name.endsWith("xml")
        }?.firstOrNull()
        return if (reportXmlPath != null) {
            unusedResourcesFlowFromLint(reportRoot, reportXmlPath)
        } else {
            emptyMap()
        }
    }

    override fun runInternal(indexObj: Path, isDry: Boolean) {
        TODO("Not yet implemented")
    }

}

fun resources(module: File, pathDetect: (String) -> Boolean, fileDetect: (File) -> Boolean): Map<String, Set<String>> {
    val file = File(module, "src/main/res/")
    val drawables = file.list { _, name ->
        pathDetect(name)
    }.orEmpty()
    return drawables.flatMap { subDrawable ->
        val subDrawables = File(file, subDrawable)
        subDrawables.listFiles().orEmpty().filter {
            fileDetect(it)
        }.map {
            it
        }
    }.groupBy {
        it.drawableName()
    }.mapValues {
        it.value.map { file1 ->
            file1.absolutePath
        }.toSet()
    }
}