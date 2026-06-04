package com.x.lasergrbl_mobile.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GcodeStreamer(
    private val scope: CoroutineScope,
    private val transport: GrblTransport,
) {
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var streamJob: Job? = null

    private val _progress = MutableStateFlow(StreamProgress())
    val progress: StateFlow<StreamProgress> = _progress

    private val _events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<StreamEvent> = _events

    fun start(lines: List<GcodeLine>) {
        stop()
        drainResponses()
        _progress.value = StreamProgress(totalLines = lines.size, running = true)
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                for (line in lines) {
                    waitIfPaused()
                    if (!_progress.value.running) break

                    _progress.value = _progress.value.copy(sentLines = _progress.value.sentLines + 1, currentLine = line)
                    _events.emit(StreamEvent(StreamEvent.Kind.Sent, "发送第 ${line.number} 行: ${line.command}", line))
                    try {
                        transport.writeLine(line.command)
                    } catch (t: Throwable) {
                        _progress.value = _progress.value.copy(
                            running = false,
                            errorCount = _progress.value.errorCount + 1,
                        )
                        _events.emit(
                            StreamEvent(
                                StreamEvent.Kind.Error,
                                "第 ${line.number} 行发送失败: ${t.message ?: "未知错误"}",
                                line,
                            )
                        )
                        break
                    }

                    val response = waitForAcknowledgement()
                    when (response) {
                        is Response.Ok -> {
                            _progress.value = _progress.value.copy(acknowledgedLines = _progress.value.acknowledgedLines + 1)
                            _events.emit(StreamEvent(StreamEvent.Kind.Ok, "第 ${line.number} 行完成", line))
                        }
                        is Response.Error -> {
                            _progress.value = _progress.value.copy(
                                acknowledgedLines = _progress.value.acknowledgedLines + 1,
                                errorCount = _progress.value.errorCount + 1,
                            )
                            _events.emit(StreamEvent(StreamEvent.Kind.Error, "第 ${line.number} 行错误: ${response.message}", line))
                        }
                        is Response.Alarm -> {
                            _progress.value = _progress.value.copy(running = false, errorCount = _progress.value.errorCount + 1)
                            _events.emit(StreamEvent(StreamEvent.Kind.Error, "设备报警: ${response.message}", line))
                            break
                        }
                        else -> Unit
                    }
                }

                val finished = _progress.value.running &&
                    _progress.value.acknowledgedLines >= _progress.value.totalLines
                _progress.value = _progress.value.copy(running = false, paused = false, currentLine = null)
                _events.emit(
                    if (finished) StreamEvent(StreamEvent.Kind.Finished, "任务发送完成")
                    else StreamEvent(StreamEvent.Kind.Stopped, "任务已停止")
                )
            } catch (_: CancellationException) {
                return@launch
            } catch (t: Throwable) {
                _progress.value = _progress.value.copy(running = false, paused = false, currentLine = null)
                _events.emit(StreamEvent(StreamEvent.Kind.Error, "任务发送异常: ${t.message ?: "未知错误"}"))
            }
        }
    }

    fun pause() {
        if (_progress.value.running && !_progress.value.paused) {
            scope.launch(Dispatchers.IO) {
                transport.writeRealtime("!")
                _progress.value = _progress.value.copy(paused = true)
                _events.emit(StreamEvent(StreamEvent.Kind.Paused, "已暂停"))
            }
        }
    }

    fun resume() {
        if (_progress.value.running && _progress.value.paused) {
            scope.launch(Dispatchers.IO) {
                transport.writeRealtime("~")
                _progress.value = _progress.value.copy(paused = false)
                _events.emit(StreamEvent(StreamEvent.Kind.Resumed, "继续运行"))
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        if (_progress.value.running || _progress.value.paused) {
            _progress.value = _progress.value.copy(running = false, paused = false, currentLine = null)
        }
    }

    suspend fun enqueueResponse(response: Response) {
        commands.send(Command.Response(response))
    }

    private fun drainResponses() {
        while (commands.tryReceive().isSuccess) {
        }
    }

    private suspend fun waitIfPaused() {
        while (_progress.value.paused && _progress.value.running) {
            delay(50)
        }
    }

    private suspend fun waitForAcknowledgement(): Response {
        while (true) {
            when (val command = commands.receive()) {
                is Command.Response -> when (command.response) {
                    is Response.Ok,
                    is Response.Error,
                    is Response.Alarm -> return command.response
                    else -> Unit
                }
            }
        }
    }

    private sealed interface Command {
        data class Response(val response: com.x.lasergrbl_mobile.core.Response) : Command
    }
}

interface GrblTransport {
    suspend fun writeLine(line: String)
    suspend fun writeRealtime(command: String)
}
