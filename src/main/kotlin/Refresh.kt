import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.FSDirectory
import java.io.File
import java.math.BigInteger
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*
import kotlin.system.exitProcess


private fun diffFiles(
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

fun calculateMD5(input: String): String {
    val md = MessageDigest.getInstance("MD5")
    val messageDigest = md.digest(input.toByteArray())
    val no = BigInteger(1, messageDigest)
    var hashText = no.toString(16)
    while (hashText.length < 32) {
        hashText = "0$hashText"
    }
    return hashText
}

fun refreshIndex(indexPathObj: Path?, srcFolders: List<Pair<String, FileChangeMode>>) {
    FSDirectory.open(indexPathObj).use { directory ->
        StandardAnalyzer().use { analyzer ->
            IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                srcFolders.forEach { (it, mode) ->
                    val file = File(it)
                    val path = file.absolutePath
                    val id = calculateMD5(path)
                    when (mode) {
                        FileChangeMode.DELETE -> writer.deleteDocuments(Term("id", id))
                        FileChangeMode.NEW -> writer.addDocument(Document().apply {
                            val indexed = file.readText().indexed()
                            add(StringField("id", calculateMD5(it), Field.Store.NO))
                            add(StoredField("path", path))
                            add(TextField("content", indexed, Field.Store.NO))
                        })

                        else -> {
                            val indexed = file.readText().indexed()
                            writer.updateDocument(
                                Term("id", id),
                                listOf(
                                    StringField("id", "id", Field.Store.NO),
                                    TextField("content", indexed, Field.Store.NO),
                                    StoredField("path", path)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

val regex = Regex("[!\"#\$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^`{|}~\\p{S}\\s\\n\\r]+")

private fun String.indexed(): String {
    return split(regex).toSet().joinToString(" ")
}

fun refreshFolders(
    oldSha: String,
    currentSha: String,
    project: File,
    module: File
): List<Pair<String, FileChangeMode>> {
    val srcFolders = if (oldSha.isNotEmpty()) {
        val predicate: (String) -> Boolean = {
            it.endsWith(".kt") || it.endsWith(".xml")
        }
        diffFiles(oldSha, currentSha, project, "M").filter(predicate).map {
            File(project, it).absolutePath to FileChangeMode.CHANGE
        } + diffFiles(oldSha, currentSha, project, "A").filter(predicate).map {
            File(project, it).absolutePath to FileChangeMode.NEW
        } + diffFiles(oldSha, currentSha, project, "D").filter(predicate).map {
            File(project, it).absolutePath to FileChangeMode.DELETE
        }
    } else {
        val r = Regex("[ \\w\\W]+?: \\[([\\w\\W/ ,]+?)]")

        val exec = Runtime.getRuntime().exec("sh gradlew ${module.name}:sourceSets", null, project)
        val re = exec.waitFor()
        val readText = exec.inputStream.bufferedReader().readText()
        val list = if (re == 0) {
            readText.split("\n").mapNotNull {
                r.find(it)?.groups?.get(1)?.value
            }.flatMap {
                it.split(", ")
            }.toSet().map {
                File(project, it).absolutePath
            }
        } else {
            val codeFolder = File(module, "src/main")
            listOf(codeFolder.absolutePath)
        }

        val stack = LinkedList<String>()
        stack.addAll(list)
        val srcFolders = mutableListOf<Pair<String, FileChangeMode>>()
        while (stack.isNotEmpty()) {
            val pathname = stack.pollFirst()
            File(pathname).list()?.forEach {
                val element = File(pathname, it).absolutePath
                when {
                    File(pathname, it).isDirectory -> stack.add(element)

                    File(pathname, it).name.endsWith(".kt") || File(pathname, it).let {
                        it.name.endsWith(".xml") && !it.absolutePath.contains("navigation")
                    } ->
                        srcFolders.add(element to FileChangeMode.NEW)
                }
            }

        }
        srcFolders
    }
    return srcFolders
}

fun getCurrentSha(project: File): String {
    val process = ProcessBuilder("git", "rev-parse", "HEAD").directory(project).start()
    val result = process.waitFor()
    if (result != 0) exitProcess(result)
    return process.inputStream.bufferedReader().readText().trim()
}
