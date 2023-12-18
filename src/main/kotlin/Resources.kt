import com.improve_future.case_changer.toCamelCase
import com.j256.simplemagic.ContentInfoUtil
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.nio.file.Path
import javax.xml.parsers.SAXParserFactory

typealias ResourceName = String

typealias ResourcePath = String

typealias ResourceSet = Set<ResourcePath>

typealias Resources = Map<ResourceName, ResourceSet>

fun File.drawableName() = name.split(".").first()

fun File.extractImageDimension(
    contentInfoUtil: ContentInfoUtil
) = contentInfoUtil.findMatch(this).message.split(",")[1].split("x").map { dimension ->
    dimension.trim().toInt()
}

abstract class Detector(val module: File) {

    protected val data = mutableMapOf<String, Set<String>>()
    private var initialized = false
    fun detect(): Resources {
        if (initialized) return data
        data.putAll(detectInternal().filter {
            it.key.isNotEmpty()
        })
        initialized = true
        return data
    }

    protected abstract fun detectInternal(): Resources

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
                it
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
                it.toCamelCase() + "Binding",
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
            listOf(it)
        }
        println("raw total ${data.size} delete $count $c space ${space.toFloat() / 1048576} MB")
    }

}

class ReportDetector(module: File) : Detector(module) {
    override fun detectInternal(): Resources {
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

class NavigationDetector(module: File) : Detector(module) {
    override fun detectInternal() = xmlResources(module, { it.startsWith("navigation") }, {
        it.extension == "xml"
    }) { qName, attributes ->
        if (qName != "fragment") null
        else {
            val id = attributes?.getValue("android:id")?.let {
                it.substring(it.lastIndexOf("/") + 1)
            }
            val name = attributes?.getValue("android:name")
            if (id != null || name != null) "$id-$name" else null
        }
    }

    override fun runInternal(indexObj: Path, isDry: Boolean) {
        var fragmentFileSum = 0L
        val (count, c, space) = deleteUnusedXmlField(indexObj, module, data, isDry, "fragment", { attrs, u, searcher, parser ->
            val ids = u.map {
                it.split("-").first()
            }
            val value = attrs?.getValue("android:id")?.let {
                it.substring(it.lastIndexOf("/") + 1)
            }
            if (ids.contains(value)) {
                val fullName = attrs?.getValue("android:name")
                if (fullName != null) {
                    val name = fullName.substring(fullName.lastIndexOf("."))
                    val path = fullName.replace(".", "/")
                    val fragmentFile = File(module, "src/main/java/${path}.kt")
                    val deleteFragment = searcher.search(parser.parse(name), 1000).scoreDocs.all {
                        searcher.doc(it.doc).get("path") == fragmentFile.absolutePath
                    }
                    if (deleteFragment) {
                        fragmentFileSum += fragmentFile.length()
                        if (isDry)
                            println("delete $fragmentFile")
                        else {
                            fragmentFile.delete()
                        }
                    }
                }
                true
            } else {
                false
            }
        }) {
            val (id, fullName) = it.split("-")
            val name = fullName.substring(fullName.lastIndexOf(".") + 1)
            listOf(id, "${name}Args")
        }
        println("navigation total ${data.size} delete $count $c space ${(space + fragmentFileSum).toFloat() / 1048576} MB")
    }

}

abstract class SimpleXmlDetector(module: File): Detector(module) {

    abstract val tagName: String
    override fun detectInternal() =
        xmlResources(module, { it.startsWith(tagName) || it.startsWith("values") }, {
            it.absolutePath.split("/").contains(tagName) || it.name == "${tagName}s.xml"
        }) { qName, attributes ->
            if (qName == tagName) attributes?.getValue("name") else null
        }

    override fun runInternal(indexObj: Path, isDry: Boolean) {
        val (count, c, space) = deleteUnusedXmlField(indexObj, module, data, isDry, tagName, { attributes, u, _, _ ->
            val name = attributes?.getValue("name")
            u.contains(name)
        }) {
            listOf(it)
        }
        println("$tagName total ${data.size} delete $count $c space ${space.toFloat() / 1048576} MB")
    }

}

class ColorDetector(module: File) : SimpleXmlDetector(module) {
    override val tagName: String
        get() = "color"

}

class DimenDetector(module: File) : SimpleXmlDetector(module) {
    override val tagName: String
        get() = "dimen"

}

/**
 * 解析指定目录指定文件类型的所有文件。
 * @return 返回的数据包含文件名和对应的文件
 */
fun resources(
    module: File,
    pathDetect: (String) -> Boolean,
    fileDetect: (File) -> Boolean
): Resources {
    val resPath = File(module, "src/main/res/")
    val paths = resPath.list { _, name ->
        pathDetect(name)
    }.orEmpty()
    return paths.flatMap { subPath ->
        File(resPath, subPath).listFiles().orEmpty().filter {
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

/**
 * 解析指定目录指定文件类型的所有文件。
 * @return 返回的数据包含文件名和对应的文件
 */
fun xmlResources(
    module: File,
    pathDetect: (String) -> Boolean,
    fileDetect: (File) -> Boolean,
    getId: (String?, Attributes?) -> String?,
): Resources {
    val newSAXParser = SAXParserFactory.newInstance().newSAXParser()
    val resPath = File(module, "src/main/res/")
    val paths = resPath.list { _, name ->
        pathDetect(name)
    }.orEmpty()
    return paths.flatMap { subPath ->
        File(resPath, subPath).listFiles().orEmpty<File>().filter {
            fileDetect(it)
        }.map {
            it
        }
    }.flatMap { file ->
        val list = mutableListOf<ResourceName>()
        newSAXParser.parse(file, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                getId(qName, attributes)?.let {
                    list.add(it)
                }
            }
        })
        val absolutePath: ResourcePath = file.absolutePath
        list.map {
            absolutePath to it
        }
    }.groupBy { (_, name) ->
        name
    }.mapValues {
        it.value.map { (path) ->
            path
        }.toSet()
    }
}