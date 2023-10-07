import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.FSDirectory
import java.io.File
import java.nio.file.Path
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

fun refreshIndex(indexPathObj: Path?, srcFolders: List<Pair<String, FileChangeMode>>) {
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
        val codeFolder = File(module, "src/main")

        val stack = LinkedList<String>()
        stack.add(codeFolder.absolutePath)
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

fun getCurrentSha(project: File): String {
    val process = ProcessBuilder("git", "rev-parse", "HEAD").directory(project).start()
    val result = process.waitFor()
    if (result != 0) exitProcess(result)
    return process.inputStream.bufferedReader().readText().trim()
}
