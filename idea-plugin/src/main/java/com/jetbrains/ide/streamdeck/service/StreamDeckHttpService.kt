@file:Suppress("INLINE_FROM_HIGHER_PLATFORM")
// https://youtrack.jetbrains.com/issue/KTIJ-20816/Bogus-error-Cannot-inline-bytecode-built-with-JVM-target-11-into-bytecode-that-is-being-built-with-JVM-target-1.8.#focus=Comments-27-7378342.0-0

package com.jetbrains.ide.streamdeck.service

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.ex.ApplicationInfoEx
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.PlatformUtils
import com.intellij.util.io.isLocalOrigin
import com.intellij.util.io.jackson.obj
import com.jetbrains.ide.streamdeck.ActionServer
import com.jetbrains.ide.streamdeck.ActionServerListener.Companion.fireServerStatusChanged
import com.jetbrains.ide.streamdeck.settings.ActionServerSettings
import com.jetbrains.ide.streamdeck.settings.ActionServerSettings.Companion.getInstance
import com.jetbrains.ide.streamdeck.util.ActionExecutor
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.RestService
import org.jetbrains.ide.writeApplicationInfoJson
import org.jetbrains.io.response
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * @api {get} /action Execute an IDE action.
 */


internal class StreamDeckHttpService : RestService() {
  companion object {
    @JvmField
    val serverLog = StringBuffer()
  }

  private val defaultDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS ")
  override fun getServiceName() = "action"

  override fun isOriginAllowed(request: HttpRequest): OriginCheckResult {
    val originAllowed = super.isOriginAllowed(request)
//    if (originAllowed == OriginCheckResult.FORBID && request.isEduToolsPluginRelated()) {
//      return OriginCheckResult.ALLOW
//    }
    return originAllowed
  }

  fun sendError(out: String, request: HttpRequest, context: ChannelHandlerContext) {
    var bytes = out.toByteArray()
    val response = response("application/json", Unpooled.wrappedBuffer(bytes, 0, bytes.size))
    response.setStatus(HttpResponseStatus.FORBIDDEN)
    sendResponse(request, context, response)
  }

  private fun log(msg: String) {
    val msg = defaultDateFormat.format(Calendar.getInstance().time) + msg + "\n"
    print(msg)
    serverLog.append(msg)
    fireServerStatusChanged()
  }

  override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
    if(!ActionServerSettings.getInstance().enable) return null
    val byteOut = BufferExposingByteArrayOutputStream()
    log("stream deck req ${request.uri()}")
    val actionId: String = request.uri().substring("/api/action/".length)
    val passwordHeader = request.headers().getAsString("Authorization")
    val password = ActionServerSettings.getInstance().password
    if (StringUtil.isNotEmpty(password)) {
      if (password != passwordHeader) {
        sendError("Bad password provided", request, context)
        return null
      }
    }

    println("actionId = $actionId")
    // WelcomeScreen.CreateNewProject Run
    // WelcomeScreen.CreateNewProject Run
    val action = ActionManager.getInstance().getAction(actionId)
    ActionExecutor.performAction(
      action, null,
      !getInstance().focusOnly
    )
    writeApplicationInfoJson(byteOut, urlDecoder, request.isLocalOrigin())
    send(byteOut, request, context)
    return null
  }
}

fun writeApplicationInfoJson(out: OutputStream, urlDecoder: QueryStringDecoder?, isLocalOrigin: Boolean) {
  JsonFactory().createGenerator(out).useDefaultPrettyPrinter().use { writer ->
    writer.obj {
      writeAboutJson(writer)

      // registeredFileTypes and more args are supported only for explicitly trusted origins
      if (!isLocalOrigin) {
        return
      }
    }
  }
}

fun writeAboutJson(writer: JsonGenerator) {
  var appName = ApplicationInfoEx.getInstanceEx().fullApplicationName
  if (!PlatformUtils.isIdeaUltimate()) {
    val productName = ApplicationNamesInfo.getInstance().productName
    appName = appName
      .replace("$productName ($productName)", productName)
      .removePrefix("JetBrains ")
  }
  writer.writeStringField("name", appName)
  writer.writeStringField("productName", ApplicationNamesInfo.getInstance().productName)

  val build = ApplicationInfo.getInstance().build
  writer.writeNumberField("baselineVersion", build.baselineVersion)
  if (!build.isSnapshot) {
    writer.writeStringField("buildNumber", build.asStringWithoutProductCode())
  }
}