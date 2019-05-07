@file:Suppress("RedundantVisibilityModifier")

package org.metalscraps.eso.lang.lib.util

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.metalscraps.eso.lang.lib.bean.ID
import org.metalscraps.eso.lang.lib.bean.PO
import org.metalscraps.eso.lang.lib.config.AppConfig
import org.metalscraps.eso.lang.lib.config.AppVariables
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.AWTError
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.ForkJoinPool
import java.util.regex.Pattern
import java.util.stream.Collectors
import java.util.zip.CRC32
import javax.swing.filechooser.FileSystemView

class Utils {

    companion object {

        private val versionMap = mutableMapOf<String, String>()
        private val projectMap = mutableMapOf<String, MutableList<String>>()
        private var vars: AppVariables = AppVariables
        val logger:Logger = LoggerFactory.getLogger(Utils::class.java)

        /* ////////////////////////////////////////////////////
        // 데이터 핸들링
        */ ////////////////////////////////////////////////////

        fun textParse(path:Path, keyGroup:Int = 2): MutableMap<String, PO> {

            val poMap = HashMap<String, PO>()
            val fileName = getName(path)

            val sb = StringBuilder(Files.readString(path, AppConfig.CHARSET).toChineseOffset())
            val find = "\\\"\\\""
            val replace = "\""
            val length = find.length

            var idx = sb.indexOf(find)
            while(idx != -1) {
                sb.replace(idx, idx+length, replace)
                idx = sb.indexOf(find)
            }

            val source = sb.toString()
            val pattern = getPattern(path)
            val m = pattern.matcher(source)
            val isPOPattern = pattern == AppConfig.POPattern

            while (m.find()) {
                // 원문에서 \n가 있지만 정규식이 파싱하는 순간 무시되버림. 멀티라인→싱글라인 되므로 백업 \n 개행문자로 변경
                val po = PO(
                        id = m.group(2),
                        source = m.group(6).replace("\\n","\n").replace("\\r","\r"),
                        target = m.group(7).replace("\\n","\n").replace("\\r","\r"),
                        fileName = fileName
                )
                if (isPOPattern && m.group(1) != null && m.group(1) == "#, fuzzy") po.isFuzzy = true
                poMap[m.group(keyGroup)] = po
            }
            return poMap
        } // textParse

        private fun getMergedPOtoMap(fileList: Collection<Path>): MutableMap<String, PO> {
            val map = HashMap<String, PO>()

            for (x in fileList) {
                val fileName: String = getName(x)

                // pregame 쪽 데이터
                if (fileName in arrayOf("00_EsoUI_Client", "00_EsoUI_Pregame")) continue

                map.putAll(textParse(x))
                logger.trace(x.toString())
            }

            map.computeIfPresent("242841733-0-54340") { _, v -> v.target = "매지카 물약".toChineseOffset(); v; }
            return map
        } // getMergedPOtoMap

        fun getMergedPO(fileList: MutableList<Path>): ArrayList<PO> {
            val sourceList = ArrayList<PO>(getMergedPOtoMap(fileList).values)
            Collections.sort(sourceList, PO.comparator)
            return sourceList
        } // getMergedPO

        public fun makeLANGwithLog(path:Path, poList: MutableList<PO>, writeFileName:Boolean = false, beta:Boolean = false) {
            val timeTaken = LocalTime.now()
            makeLANG(path, poList, writeFileName, beta)
            logger.info("${path.fileName} ${timeTaken.until(LocalTime.now(), ChronoUnit.SECONDS)}초")
        } // makeLANGwithLog

        fun makeLANG(path: Path, poList: MutableList<PO>, writeFileName:Boolean = false, beta:Boolean = false) {
            var offset = 0
            FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use {
                it.write(ByteBuffer.allocate(Int.SIZE_BYTES * 2).putInt(2).putInt(poList.size).flip())
                for (p in poList) {
                    it.write(ByteBuffer.allocate(16).putInt(p.id1).putInt(p.id2).putInt(p.id3).putInt(offset).flip())
                    offset += p.getLengthForLang(writeFileName, beta) + 1
                }
                for (p in poList) {
                    val x = p.getTextForLang(writeFileName, beta)
                    it.write(ByteBuffer.allocate(x.size + 1).put(x).put(0).flip())
                }
            }
        } // makeLANG

        /* ////////////////////////////////////////////////////
        // 데이터 핸들링 끝
        */ ////////////////////////////////////////////////////


        /* ////////////////////////////////////////////////////
        // Path/이름 제어
        */ ////////////////////////////////////////////////////

        private fun getPattern(path:Path): Pattern {
            val ext = getExtension(path)
            if (ext == "po" || ext == "po2") return AppConfig.POPattern
            else if (ext == "csv") return AppConfig.CSVPattern
            else {
                logger.error("알 수 없는 패턴 ${path.toAbsolutePath()}")
                System.exit(0)
            }
            return AppConfig.CSVOffsetPattern
        }

        fun listFiles(path:Path, ext:String) : MutableList<Path> {
            try { return Files.list(path).filter { x -> !Files.isDirectory(x) && getExtension(x) == ext }.collect(Collectors.toList()) }
            catch (e: IOException) { e.printStackTrace() }
            return ArrayList()
        }

        fun getName(path: Path): String {
            if (Files.isDirectory(path)) logger.warn("파일 아님 ${path.toAbsolutePath()}")
            val x = path.fileName.toString()
            return x.substring(0, x.lastIndexOf('.'))
        }

        fun getExtension(path: Path): String {
            if (Files.isDirectory(path)) logger.warn("파일 아님 ${path.toAbsolutePath()}")
            val x = path.fileName.toString()
            return x.substring(x.lastIndexOf('.') + 1)
        }

        fun getESOLangDir(): Path {
            return getESODir().resolve("live/addOns/gamedata/lang")
        }

        fun getESODir(): Path {
            return Paths.get(System.getProperty("user.home")).resolve("Documents/Elder Scrolls Online/")
        }


        /* ////////////////////////////////////////////////////
        // Path/이름 끝
        */ ////////////////////////////////////////////////////


        /* ////////////////////////////////////////////////////
        // 통신 부분
        */ ////////////////////////////////////////////////////

        fun getDefaultRestClient(domain: String): HttpRequest {
            return HttpRequest.newBuilder().uri(URI.create(domain)).header("Accept", "application/json").build()
        } // getDefaultRestClient

        private fun getBodyFromHTTPsRequest(request: HttpRequest): JsonNode {

            val client = HttpClient.newHttpClient()
            val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
            val body = response.body()
            logger.trace(body)

            return ObjectMapper().readTree(body)
        } // getBodyFromHTTPsRequest

        fun getLatestVersion(projectName: String): String {
            if (versionMap.containsKey(projectName)) return versionMap.getValue(projectName)
            val request = getDefaultRestClient("${AppConfig.ZANATA_DOMAIN}rest/projects/p/$projectName")

            val jsonNode = getBodyFromHTTPsRequest(request)
            var serverVer: Array<String>? = null

            for (node in jsonNode.get("iterations")) {
                if (node.get("status").toString().equals("\"ACTIVE\"", ignoreCase = true)) { // && temp > version
                    val tempVer = node.get("id").asText().split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    if (serverVer == null) {
                        serverVer = tempVer
                        continue
                    }

                    val length = Math.max(tempVer.size, serverVer.size)
                    for (i in 0..length) {
                        val temp = if (i < tempVer.size) Integer.parseInt(tempVer[i]) else 0
                        val serv = if (i < serverVer!!.size) Integer.parseInt(serverVer[i]) else 0
                        if (temp < serv) break
                        if (temp > serv) serverVer = tempVer
                    }
                }
            }
            if (serverVer == null) serverVer = arrayOf("0.0.0")
            versionMap[projectName] = serverVer.joinToString(".")
            return versionMap.getValue(projectName)
        } // getLatestVersion

        private fun getFileNames(projectName: String): MutableList<String> {
            val request = getDefaultRestClient("${AppConfig.ZANATA_DOMAIN}rest/projects/p/$projectName/iterations/i/${getLatestVersion(projectName)}/r")
            return getBodyFromHTTPsRequest(request).map { it.findValue("name").toString().removeSurrounding("\"") }.toMutableList()
        } // getFileNames


        fun downloadPOs() {
            val timeTaken = LocalTime.now()
            downloadPO("ESO-item")
            downloadPO("ESO-skill")
            downloadPO("ESO-system")
            downloadPO("ESO-book")
            downloadPO("ESO-story")
            logger.info("총 ${timeTaken.until(LocalTime.now(), ChronoUnit.SECONDS)}초")
        } // downloadPOs

        private fun downloadPO(projectName: String, remainFiles:MutableList<String> = mutableListOf()) {
            val url = "${AppConfig.ZANATA_DOMAIN}rest/file/translation/$projectName/${getLatestVersion(projectName)}/ko/po?docId="
            val fileNames = if(remainFiles.size == 0) getFileNames(projectName) else remainFiles
            val poDir = vars.poDir

            if (!Files.exists(poDir)) Files.createDirectories(poDir)
            val threadPool = ForkJoinPool(2) // 자나타 API 리밋
            threadPool.submit {
                fileNames.parallelStream().forEach {
                    val fileName = it
                    // 우리가 사용하는 데이터 아님.
                    if (fileName in arrayOf("00_EsoUI_Client", "00_EsoUI_Pregame")) return@forEach

                    val ltStart = LocalTime.now()
                    var fileURL = url + fileName
                    fileURL = fileURL.replace(" ", "%20")

                    val pPO = poDir.resolve("$fileName.po")
                    if (!Files.exists(pPO)) {
                        try {
                            val server = Channels.newChannel(URL(fileURL).openStream())
                            val out = FileChannel.open(pPO, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                            out.transferFrom(server, 0, Long.MAX_VALUE)
                        } catch (e: IOException) {
                            val msg = e.message!!
                            if(msg.contains("response code: 429") || msg.contains("Premature EOF") || msg.contains("connection closed locally")) {
                                remainFiles.add(fileName)
                                logger.warn("오류, 재시도 $fileName ${e.message}")
                                if (Files.exists(pPO)) try { Files.delete(pPO!!) } catch (e1: IOException) { }
                            } else e.printStackTrace()
                        } catch (e: Exception) {
                            logger.error(e.message)
                            e.printStackTrace()
                        }
                    }
                    val ltEnd = LocalTime.now()
                    logger.debug("downloaded zanata file  [$fileName] to local [$pPO] ${ltStart.until(ltEnd, ChronoUnit.SECONDS)}초")
                }
            }.get()

            if(remainFiles.size > 0) {
                Thread.sleep(5 * 1000)
                downloadPO(projectName)
            }
        } // downloadPO

        fun getDocuments(projectName: String): MutableList<String> {
            val list = projectMap[projectName] ?: mutableListOf()
            if(list.size == 0) list.addAll(getFileNames(projectName))
            return list
        }

        fun getProjectMap(): MutableMap<String, MutableList<String>> {
            if (projectMap.isEmpty()) {
                logger.info("rest/projects")

                //val request = getDefaultRestClient("${AppConfig.ZANATA_DOMAIN}rest/projects?q=ESO-")
                //val jsonNode = getBodyFromHTTPsRequest(request)

                for(x in listOf("story", "system", "skill", "item", "book")) {
                    val id = "ESO-$x"
                    projectMap[id] = getFileNames(id)
                }

            }

            for (x in projectMap.keys) getDocuments(x)
            return projectMap
        }

        @Throws(Exception::class)
        fun getProjectNameByDocument(id: ID): String {
            if (!id.isFileNameHead()) throw ID.NotFileNameHead()
            for (x in getProjectMap().entries) for (y in x.value)
                if (y.equals(id.head, true)) {
                    id.head = y
                    return x.key
                }
            throw Exception("프로젝트 못찾음 /$id")
        }

        fun crc32(p: Path): Long {
            val crc = CRC32()
            try { if (Files.notExists(p) || Files.size(p) <= 0) return -1 }
            catch (e: IOException) { e.printStackTrace() }

            val buffer = Files.readAllBytes(p)
            crc.update(buffer, 0, buffer.size)
            return crc.value
        } // crc32

        /* ////////////////////////////////////////////////////
        // 통신 부분 끝
        */ ////////////////////////////////////////////////////

        /* ////////////////////////////////////////////////////
        // 프로세스 러너
        */ ////////////////////////////////////////////////////

        @Throws(IOException::class, InterruptedException::class)
        fun processRun(baseDirectory: Path, command: String) { processRun(baseDirectory, command, ProcessBuilder.Redirect.INHERIT) }

        @Throws(IOException::class, InterruptedException::class)
        fun processRun(baseDirectory: Path, command: String, redirect: ProcessBuilder.Redirect) {
            logger.debug(command)
            val pb = ProcessBuilder()
                    .directory(baseDirectory.toFile())
                    .command(*command.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(redirect)
            pb.start().waitFor()
        }

        /* ////////////////////////////////////////////////////
        // 프로세스 러너 끝
        */ ////////////////////////////////////////////////////

    } // companion object
}