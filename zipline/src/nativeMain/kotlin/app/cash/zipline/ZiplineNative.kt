/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline

import app.cash.zipline.internal.bridge.InboundBridge
import app.cash.zipline.internal.bridge.OutboundBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.modules.SerializersModule

// TODO move this to actual to engineMain
actual abstract class Zipline {
  abstract val quickJs: QuickJs

  abstract val dispatcher: CoroutineDispatcher

  actual abstract val engineVersion: String

  actual abstract val serviceNames: Set<String>

  actual abstract val clientNames: Set<String>

  actual fun <T : Any> get(name: String, serializersModule: SerializersModule): T {
    error("unexpected call to Zipline.get: is the Zipline plugin configured?")
  }

  @PublishedApi
  internal abstract fun <T : Any> get(name: String, bridge: OutboundBridge<T>): T

  actual fun <T : Any> set(name: String, serializersModule: SerializersModule, instance: T) {
    error("unexpected call to Zipline.set: is the Zipline plugin configured?")
  }

  @PublishedApi
  internal abstract fun <T : Any> set(name: String, bridge: InboundBridge<T>)

  /**
   * Release resources held by this instance. It is an error to do any of the following after
   * calling close:
   *
   *  * Call [get] or [set].
   *  * Accessing [quickJs].
   *  * Accessing the objects returned from [get].
   */
  abstract fun close()

  companion object {
    fun create(dispatcher: CoroutineDispatcher): Zipline {
      TODO()
    }
  }
}
