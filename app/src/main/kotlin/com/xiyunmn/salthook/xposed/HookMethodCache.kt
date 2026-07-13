package com.xiyunmn.salthook.xposed

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import com.xiyunmn.salthook.BuildConfig
import com.xiyunmn.salthook.diagnostics.SaltDiagnostics
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Method
import java.util.Properties

class HookMethodCache private constructor(
    application: Application,
    hostInfo: PackageIdentity,
    moduleInfo: PackageIdentity,
) {
    private val file: File
    private val hostPackageName: String
    private val hostVersionCode: Long
    private val hostLastUpdateTime: Long
    private val modulePackageName: String
    private val moduleVersionCode: Long
    private val moduleLastUpdateTime: Long
    private val moduleCacheRevision: Long
    private val properties = Properties()
    private var loadedValid = false

    init {
        val directory = File(application.filesDir, DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
            SaltDiagnostics.warn("hooks.cache", "failed to create cache directory " + directory.absolutePath)
        }
        file = File(directory, FILE_NAME)
        hostPackageName = application.packageName
        hostVersionCode = hostInfo.versionCode
        hostLastUpdateTime = hostInfo.lastUpdateTime
        modulePackageName = moduleInfo.packageName
        moduleVersionCode = moduleInfo.versionCode
        moduleLastUpdateTime = moduleInfo.lastUpdateTime
        moduleCacheRevision = BuildConfig.HOOK_CACHE_REVISION
        load()
        writeHeader()
    }

    fun readMethods(ownerClass: Class<*>, group: String, classLoader: ClassLoader): List<Method> {
        if (!loadedValid) {
            SaltDiagnostics.trace("hooks.cache", "miss group=$group reason=no-valid-cache")
            return ArrayList()
        }

        val countText = properties.getProperty("$group.count")
        if (countText == null) {
            SaltDiagnostics.trace("hooks.cache", "miss group=$group reason=no-count")
            return ArrayList()
        }

        val count = try {
            countText.toInt()
        } catch (exception: NumberFormatException) {
            SaltDiagnostics.warn("hooks.cache", "invalid count group=$group value=$countText")
            return ArrayList()
        }
        if (count <= 0) {
            SaltDiagnostics.trace("hooks.cache", "miss group=$group reason=empty")
            return ArrayList()
        }

        val methods = ArrayList<Method>(count)
        for (index in 0 until count) {
            val descriptor = properties.getProperty("$group.$index")
            if (descriptor == null || descriptor.isEmpty()) {
                SaltDiagnostics.warn("hooks.cache", "missing descriptor group=$group index=$index")
                return ArrayList()
            }
            try {
                val method = resolveMethod(ownerClass, descriptor, classLoader)
                method.isAccessible = true
                methods.add(method)
            } catch (throwable: Throwable) {
                SaltDiagnostics.warn("hooks.cache", "resolve failed group=$group descriptor=$descriptor", throwable)
                return ArrayList()
            }
        }

        SaltDiagnostics.log("hooks.cache", "hit group=$group count=" + methods.size)
        return methods
    }

    fun writeMethods(group: String, methods: List<Method>) {
        val start = SaltDiagnostics.now()
        removeGroup(group)
        writeHeader()
        properties.setProperty("$group.count", methods.size.toString())
        for (index in methods.indices) {
            properties.setProperty("$group.$index", descriptorOf(methods[index]))
        }
        properties.setProperty("updatedAt", System.currentTimeMillis().toString())

        val tempFile = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tempFile).use { output ->
                properties.store(output, "SaltLyricon hook method cache")
            }
        } catch (exception: IOException) {
            SaltDiagnostics.warn("hooks.cache", "write failed group=$group", exception)
            return
        }

        if (file.exists() && !file.delete()) {
            SaltDiagnostics.warn("hooks.cache", "failed to replace old cache " + file.absolutePath)
            return
        }
        if (!tempFile.renameTo(file)) {
            SaltDiagnostics.warn("hooks.cache", "failed to commit cache " + tempFile.absolutePath)
            return
        }

        loadedValid = true
        SaltDiagnostics.log(
            "hooks.cache",
            "write group=$group count=" + methods.size + " elapsedMs=" + SaltDiagnostics.elapsedMs(start),
        )
    }

    private fun load() {
        if (!file.exists()) {
            SaltDiagnostics.trace("hooks.cache", "cache file missing")
            loadedValid = false
            return
        }

        val loaded = Properties()
        try {
            FileInputStream(file).use { input ->
                loaded.load(input)
            }
        } catch (exception: IOException) {
            SaltDiagnostics.warn("hooks.cache", "read failed " + file.absolutePath, exception)
            loadedValid = false
            return
        }

        val invalidReason = invalidReason(loaded)
        if (invalidReason != null) {
            SaltDiagnostics.log("hooks.cache", "ignore stale cache reason=$invalidReason path=" + file.absolutePath)
            loadedValid = false
            return
        }

        properties.putAll(loaded)
        loadedValid = true
    }

    private fun invalidReason(loaded: Properties): String? {
        if (SCHEMA_VERSION.toString() != loaded.getProperty("schema")) {
            return "schema"
        }
        if (hostPackageName != loaded.getProperty("hostPackage")) {
            return "package"
        }
        if (hostVersionCode.toString() != loaded.getProperty("hostVersionCode")) {
            return "versionCode"
        }
        if (hostLastUpdateTime.toString() != loaded.getProperty("hostLastUpdateTime")) {
            return "hostLastUpdateTime"
        }
        if (modulePackageName != loaded.getProperty("modulePackage")) {
            return "modulePackage"
        }
        if (moduleVersionCode.toString() != loaded.getProperty("moduleVersionCode")) {
            return "moduleVersionCode"
        }
        if (moduleLastUpdateTime.toString() != loaded.getProperty("moduleLastUpdateTime")) {
            return "moduleLastUpdateTime"
        }
        if (moduleCacheRevision.toString() != loaded.getProperty("moduleCacheRevision")) {
            return "moduleCacheRevision"
        }
        return null
    }

    private fun writeHeader() {
        properties.setProperty("schema", SCHEMA_VERSION.toString())
        properties.setProperty("hostPackage", hostPackageName)
        properties.setProperty("hostVersionCode", hostVersionCode.toString())
        properties.setProperty("hostLastUpdateTime", hostLastUpdateTime.toString())
        properties.setProperty("modulePackage", modulePackageName)
        properties.setProperty("moduleVersionCode", moduleVersionCode.toString())
        properties.setProperty("moduleLastUpdateTime", moduleLastUpdateTime.toString())
        properties.setProperty("moduleCacheRevision", moduleCacheRevision.toString())
    }

    private fun removeGroup(group: String) {
        val keys = ArrayList<String>()
        for (key in properties.keys) {
            val name = key.toString()
            if (name == "$group.count" || name.startsWith("$group.")) {
                keys.add(name)
            }
        }
        for (key in keys) {
            properties.remove(key)
        }
    }

    private data class PackageIdentity(
        val packageName: String,
        val versionCode: Long,
        val lastUpdateTime: Long,
    )

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val FILE_NAME = "saltlyricon-hook-cache.properties"
        private const val DIRECTORY_NAME = "salthook_diagnostics"

        @JvmStatic
        fun open(application: Application): HookMethodCache {
            val start = SaltDiagnostics.now()
            val cache = HookMethodCache(
                application,
                readPackageIdentity(application, application.packageName),
                readPackageIdentity(application, BuildConfig.APPLICATION_ID),
            )
            SaltDiagnostics.log(
                "hooks.cache",
                "open valid=" + cache.loadedValid +
                    " elapsedMs=" + SaltDiagnostics.elapsedMs(start) +
                    " path=" + cache.file.absolutePath +
                    " hostVersionCode=" + cache.hostVersionCode +
                    " hostLastUpdateTime=" + cache.hostLastUpdateTime +
                    " moduleVersionCode=" + cache.moduleVersionCode +
                    " moduleLastUpdateTime=" + cache.moduleLastUpdateTime +
                    " moduleCacheRevision=" + cache.moduleCacheRevision,
            )
            return cache
        }

        private fun descriptorOf(method: Method): String {
            val builder = StringBuilder(method.name).append('(')
            val parameterTypes = method.parameterTypes
            for (index in parameterTypes.indices) {
                if (index > 0) {
                    builder.append(',')
                }
                builder.append(parameterTypes[index].name)
            }
            return builder.append(')').toString()
        }

        @Throws(ClassNotFoundException::class, NoSuchMethodException::class)
        private fun resolveMethod(
            ownerClass: Class<*>,
            descriptor: String,
            classLoader: ClassLoader,
        ): Method {
            val open = descriptor.indexOf('(')
            val close = descriptor.lastIndexOf(')')
            if (open <= 0 || close <= open) {
                throw NoSuchMethodException(descriptor)
            }

            val methodName = descriptor.substring(0, open)
            val params = descriptor.substring(open + 1, close)
            val parameterTypes = if (params.isEmpty()) {
                emptyArray()
            } else {
                val names = params.split(",")
                Array(names.size) { index -> classForName(names[index], classLoader) }
            }
            return ownerClass.getDeclaredMethod(methodName, *parameterTypes)
        }

        @Throws(ClassNotFoundException::class)
        private fun classForName(name: String, classLoader: ClassLoader): Class<*> {
            return when (name) {
                "boolean" -> Boolean::class.javaPrimitiveType!!
                "byte" -> Byte::class.javaPrimitiveType!!
                "char" -> Char::class.javaPrimitiveType!!
                "short" -> Short::class.javaPrimitiveType!!
                "int" -> Int::class.javaPrimitiveType!!
                "long" -> Long::class.javaPrimitiveType!!
                "float" -> Float::class.javaPrimitiveType!!
                "double" -> Double::class.javaPrimitiveType!!
                "void" -> Void.TYPE
                else -> Class.forName(name, false, classLoader)
            }
        }

        @Suppress("DEPRECATION")
        private fun readPackageIdentity(application: Application, packageName: String): PackageIdentity {
            return try {
                val packageInfo = application.packageManager.getPackageInfo(packageName, 0)
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                }
                PackageIdentity(packageInfo.packageName, versionCode, packageInfo.lastUpdateTime)
            } catch (exception: PackageManager.NameNotFoundException) {
                SaltDiagnostics.warn("hooks.cache", "read package info failed package=$packageName", exception)
                PackageIdentity(packageName, 0L, 0L)
            }
        }
    }
}
