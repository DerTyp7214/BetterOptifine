import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*
import kotlin.collections.ArrayList

object Main {
    private const val versionManifest = "https://launchermeta.mojang.com/mc/game/version_manifest.json"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val path = File(System.getProperty("user.dir"), "tmp")
            path.mkdirs()
            val arguments = args.associate {
                Pair(it.split("=").first(), it.split("=").last())
            }
            if (arguments["deleteThisFuckingDirectory"] == "true") {
                path.deleteRecursively()
            } else if (arguments.isEmpty()) {
                println()
                println("Arguments:")
                println()
                println("\tversion=<version>")
                println("\toutput=<outputPath>")
                println()
            } else {
                val versionString = arguments["version"]
                if (versionString != null) {
                    val optifine = downloadOptifine(versionString, path)
                    val outputPath = arguments["output"]
                    val gson = Gson()
                    val manifest = gson.fromJson(getStringFromUrl(versionManifest), VersionManifest::class.java)
                    val version = manifest.versions.findLast {
                        it.id == arguments["version"] || "${it.id}.0" == arguments["version"]
                    }
                    arguments.forEach {
                        println("${it.key}: ${it.value}")
                    }
                    if (version != null && optifine != null) {
                        val versionPath = File(path, "versions/${version.id}")
                        versionPath.mkdirs()
                        val versionJsonText = getStringFromUrl(version.url)
                        val versionJson = gson.fromJson(versionJsonText, VersionJson::class.java)
                        Files.copy(
                            URL(versionJson.downloads.client.url).openCustomStream(),
                            File(versionPath, "${version.id}.jar").toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                        File(versionPath, "${version.id}.json").writeText(versionJsonText)
                        doExtract(optifine, path).walk().findLast {
                            it.isFile && it.name.endsWith(".jar")
                        }?.copyTo(
                            File(outputPath ?: System.getProperty("user.dir"), "OptiFile_${version.id}.jar").also { println("Copy To: ${it.absolutePath}") },
                            true
                        )
                        path.deleteRecursively()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun downloadOptifine(version: String, path: File): File? {
        val versions =
            Regex("(<a href=\"(http:\\/\\/optifine\\.net\\/adloadx\\?f=OptiFine_(.*)\\.jar)\">\\(Mirror\\)<\\/a>)").findAll(
                getStringFromUrl("https://optifine.net/downloads")
            )
        val optifineVersion = versions.find {
            val (_, _, name) = it.destructured
            name.startsWith("${version.removeSuffix(".0")}_") || name.startsWith("${version.removeSuffix(".0")}.0_")
        }
        return if (optifineVersion != null) {
            val url = optifineVersion.groupValues[2].replaceFirst("http", "https")
            val result =
                Regex("<a href=[\"'](downloadx\\?f=OptiFine_.*\\.jar&x=.{32})[\"']").find(getStringFromUrl(url))
            if (result != null) {
                val downloadUrl = url.replace(Regex("adloadx.*"), result.groupValues[1])
                val output = File(path, "OptiFile.jar")
                Files.copy(URL(downloadUrl).openCustomStream(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                output
            } else null
        } else null
    }

    private fun getStringFromUrl(url: String): String {
        val connection = URL(url).openConnection()
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4343.0 Safari/537.36"
        )
        return connection.getInputStream().bufferedReader().readText()
    }

    private fun URL.openCustomStream(): InputStream {
        val connection = openConnection()
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4343.0 Safari/537.36"
        )
        return connection.getInputStream()
    }

    private fun doExtract(optifineJar: File, dirMc: File): File {
        val child = URLClassLoader(arrayOf(optifineJar.toURI().toURL()), javaClass.classLoader)
        val installer = Class.forName("optifine.Installer", true, child)
        val dirMcLib = File(dirMc, "libraries")
        val ofVer = getMethod<String>(installer, "getOptiFineVersion")
        val ofVers = tokenize(ofVer, "_")
        val mcVer = ofVers[1]
        val ofEd = getMethod<String>(installer, "getOptiFineEdition", listOf(Array<String>::class.java), listOf(ofVers))
        val method = installer.getDeclaredMethod(
            "installOptiFineLibrary",
            String::class.java,
            String::class.java,
            File::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(null, mcVer, ofEd, dirMcLib, false)
        return dirMcLib
    }

    private fun <V> getMethod(
        clazz: Class<*>,
        name: String,
        parameterTypes: List<Class<*>> = ArrayList(),
        args: List<Any> = ArrayList()
    ): V {
        val m = clazz.getDeclaredMethod(name, *parameterTypes.toTypedArray())
        m.isAccessible = true
        return m.invoke(null, *args.toTypedArray()) as V
    }

    private fun tokenize(str: String?, delim: String?): Array<String> {
        val list: MutableList<String> = ArrayList()
        val tok = StringTokenizer(str, delim)
        while (tok.hasMoreTokens()) {
            val token = tok.nextToken()
            list.add(token)
        }
        return list.toTypedArray()
    }
}

data class VersionManifest(val latest: Latest, val versions: List<Version>)
data class Latest(val release: String, val snapshot: String)
data class Version(val id: String, val type: String, val url: String, val time: String, val releaseTime: String)

data class VersionJson(val downloads: Downloads)
data class Downloads(
    val client: ShaFile,
    val client_mappings: ShaFile,
    val server: ShaFile,
    val server_mappings: ShaFile
)

data class ShaFile(val sha1: String, val size: Long, val url: String)