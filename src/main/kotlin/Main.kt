import com.j256.simplemagic.ContentInfoUtil
import kotlinx.cli.*
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.FSDirectory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import javax.xml.parsers.SAXParserFactory
import kotlin.io.path.*
import kotlin.system.exitProcess

enum class FileChangeMode {
    NEW, CHANGE, DELETE
}

@OptIn(ExperimentalCli::class)
class DetectLarge(private val indexPathObj: Path, private val project: File, private val module: File) :
    Subcommand("DetectLarge", "检测大尺寸的drawable") {
    override fun execute() {
        refreshIndexIfNeed(indexPathObj, project, module)
        val groupBy = drawables(module)
        groupBy.filter {
            it.value.any { (_, size) ->
                size > 4 * 1024 * 1024
            }
        }.forEach { (t, u) ->
            println(t)
            u.sortedBy {
                it.second
            }.forEach {
                println("\t${it.second.toFloat() / 1048576} bytes")
            }
        }
    }


}

private fun drawables(module: File): Map<String, List<Pair<File, Int>>> {
    val contentInfoUtil = ContentInfoUtil()
    val file = File(module, "src/main/res/")
    val drawables = file.list { _, name ->
        name.startsWith("drawable")
    }.orEmpty()
    return drawables.flatMap { subDrawable ->
        val subDrawables = File(file, subDrawable)
        subDrawables.listFiles().orEmpty().filter {
            it.extension == "png"
        }.map {
            it to extractImageDimension(contentInfoUtil, it).multi()
        }
    }.groupBy {
        it.first.drawableName()
    }
}

private fun List<Int>.multi(): Int {
    return fold(1) { a, i ->
        a * i
    }
}

private fun extractImageDimension(
    contentInfoUtil: ContentInfoUtil,
    it: File?
) = contentInfoUtil.findMatch(it).message.split(",")[1].split("x").map { dimension ->
    dimension.trim().toInt()
}

@ExperimentalCli
class RemoveUnused(private val indexPathObj: Path, private val project: File, private val module: File) :
    Subcommand("RemoveUnused", "移除未使用的图片") {

    private val isDryRun by option(ArgType.Boolean, "dryRun", "d").required()
    private val full by option(ArgType.Boolean, "full", "f")

    override fun execute() {
        refreshIndexIfNeed(indexPathObj, project, module)
        if (full == true) {
            drawables(module).mapValues {
                it.value.map { (file, _) ->
                    file.absolutePath
                }.toSet()
            }
        } else {
            val reportRoot = File(module, "build/reports/")
            val reportXmlPath = reportRoot.list { _, name ->
                name.endsWith("xml")
            }?.firstOrNull()
            if (reportXmlPath != null) {
                unusedDrawableFlow(reportRoot, reportXmlPath)
            } else {
                println("找不到对应的xml 文件")
                null
            }
        }?.let {
            val (count, space) = deleteUnused(indexPathObj, it, isDryRun)
            println("total ${it.size} delete $count space ${space.toFloat() / 1048576} MB")
        }

    }

}

@OptIn(ExperimentalCli::class)
class CleanIndex(private val indexPathObj: Path, private val project: File, private val module: File) :
    Subcommand("CleanIndex", "清除现有索引，然后退出") {
    private val rebuild by option(ArgType.Boolean, "rebuild", "r", "删除索引后重新创建索引")

    @OptIn(ExperimentalPathApi::class)
    override fun execute() {
        indexPathObj.deleteRecursively()
        if (rebuild == true)
            refreshIndexIfNeed(indexPathObj, project, module)
    }

}


@OptIn(ExperimentalCli::class)
fun main(args: Array<String>) {
    println("Program arguments: ${args.joinToString()}")
    if (args.isEmpty()) return
    val moduleName = "app"
    val projectDir = args.first()
    val module = File(projectDir, moduleName)
    val project = File(projectDir)

    val indexPathObj = Paths.get(projectDir, ".index")

    val argParser = ArgParser("Qing")
    val detectLarge = DetectLarge(indexPathObj, project, module)
    val removeUnused = RemoveUnused(indexPathObj, project, module)
    val cleanIndex = CleanIndex(indexPathObj, project, module)
    argParser.subcommands(detectLarge, removeUnused, cleanIndex)
    argParser.parse(args.sliceArray(1..<args.size))
}

private fun refreshIndexIfNeed(indexPathObj: Path, project: File, module: File) {
    val shaFlag = Paths.get(indexPathObj.absolutePathString(), "qing")
    if (ensure(shaFlag)) exitProcess(1)
    val oldSha = shaFlag.readText()
    val currentSha = getCurrentSha(project)
    if (oldSha == currentSha) {
        println("index not change!")
        return
    }
    println("refresh index -> old $oldSha new $currentSha")

    val srcFolders = refreshFolders(oldSha, currentSha, project, module)
    refreshIndex(indexPathObj, srcFolders)
    shaFlag.writeText(currentSha)
}

private fun deleteUnused(
    indexPathObj: Path?,
    list: Map<String, Set<String>>,
    isDry: Boolean
): Pair<Int, Long> {
    var count = 0
    var space = 0L
    FSDirectory.open(indexPathObj).use { fsDirectory ->
        StandardAnalyzer().use { analyzer ->
            val parser = QueryParser("content", analyzer)
            DirectoryReader.open(fsDirectory).use { reader ->
                val searcher = IndexSearcher(reader)
                list.forEach { (name, group) ->
                    if (listOf(name, "R.drawable.$name").all {
                            val docs = searcher.search(parser.parse(it), 1)
                            docs.scoreDocs.isEmpty()
                        }) {
                        val fileList = group.map {
                            File(it)
                        }
                        space += fileList.map {
                            it.length()
                        }.reduce { acc, file ->
                            acc + file
                        }
                        if (!isDry)
                            fileList.forEach {
                                it.deleteOnExit()
                            }
                        count++
                    }
                }
            }
        }

    }
    return Pair(count, space)
}

/**
 * @return 返回创建文件是否失败
 */
private fun ensure(shaFlag: Path): Boolean {
    if (!shaFlag.exists()) {
        shaFlag.createParentDirectories()
        shaFlag.createFile()
    }
    return false
}

private fun getCurrentSha(project: File): String {
    val process = ProcessBuilder("git", "rev-parse", "HEAD").directory(project).start()
    val result = process.waitFor()
    if (result != 0) exitProcess(result)
    return process.inputStream.bufferedReader().readText().trim()
}

private fun refreshFolders(
    oldSha: String,
    currentSha: String,
    project: File,
    module: File
): List<Pair<String, FileChangeMode>> {
    val srcFolders = if (oldSha.isNotEmpty()) {
        val predicate: (String) -> Boolean = {
            it.endsWith(".kt") || it.endsWith(".xml")
        }
        strings(oldSha, currentSha, project, "M").filter(predicate).map {
            File(project, it).absolutePath to FileChangeMode.CHANGE
        } + strings(oldSha, currentSha, project, "A").filter(predicate).map {
            File(project, it).absolutePath to FileChangeMode.NEW
        } + strings(oldSha, currentSha, project, "D").filter(predicate).map {
            File(project, it).absolutePath to FileChangeMode.DELETE
        }
    } else {
        val codeFolder = File(module, "src/main/java")
        val layoutFolder = File(module, "src/main/res/layout")

        val stack = LinkedList<String>()
        stack.add(codeFolder.absolutePath)
        stack.add(layoutFolder.absolutePath)
        val srcFolders = mutableListOf<Pair<String, FileChangeMode>>()
        while (stack.isNotEmpty()) {
            val pathname = stack.pollFirst()
            File(pathname).list()?.forEach {
                val file = File(pathname, it)
                if (file.isFile) {
                    srcFolders.add(file.absolutePath to FileChangeMode.NEW)
                } else {
                    stack.add(file.absolutePath)
                }
            }

        }
        srcFolders
    }
    return srcFolders
}

private fun strings(
    oldSha: String,
    currentSha: String,
    project: File,
    mode: String
): List<String> {
    val process =
        ProcessBuilder("git", "diff", oldSha, currentSha, "--name-only", "--diff-filter=$mode").directory(project)
            .start()
    val processResult = process.waitFor()
    if (processResult != 0) {
        exitProcess(processResult)
    }
    val text = process.inputStream.bufferedReader().readText().trim()
    return text.split("\n").filter {
        it.isNotEmpty()
    }
}

private fun refreshIndex(indexPathObj: Path?, srcFolders: List<Pair<String, FileChangeMode>>) {
    FSDirectory.open(indexPathObj).use { directory ->
        StandardAnalyzer().use { analyzer ->
            IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                srcFolders.forEach { (it, mode) ->
                    val file = File(it)
                    when (mode) {
                        FileChangeMode.DELETE -> writer.deleteDocuments(Term("path", it))
                        FileChangeMode.NEW -> writer.addDocument(Document().apply {
                            add(StringField("path", it, Field.Store.YES))
                            add(TextField("content", file.readText(), Field.Store.NO))
                        })

                        else -> writer.updateDocument(
                            Term("path", it),
                            listOf(TextField("content", file.readText(), Field.Store.NO))
                        )
                    }
                }
            }
        }
    }
}

private fun unusedDrawableFlow(reportRoot: File, reportXmlPath: String): MutableMap<String, MutableSet<String>> {
    val newInstance = SAXParserFactory.newInstance()
    val newSAXParser = newInstance.newSAXParser()
    val reportXmlFile = File(reportRoot, reportXmlPath)

    val handler = object : DefaultHandler() {
        val map = mutableMapOf<String, MutableSet<String>>()
        var printNextLocation = false
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            super.startElement(uri, localName, qName, attributes)
            if (qName == "issue") {
                if (attributes?.getValue("id") == "UnusedResources") {
                    printNextLocation = true
                } else {
                    skippedEntity(qName)
                }
            } else if (qName == "location") {
                if (printNextLocation) {
                    val message = attributes?.getValue("file")
                    if (message?.endsWith("png") == true) {
                        val key = File(message).drawableName()
                        map.getOrPut(key) { mutableSetOf() }.add(message)
                    }
                }

            }
        }
    }
    newSAXParser.parse(reportXmlFile, handler)
    return handler.map
}

private fun File.drawableName() = name.split(".").first()