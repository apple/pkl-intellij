/**
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.intellij.util

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runInEdt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

object CompletableFutureUtil {
  fun <T, R> CompletableFuture<T>.handleOnEdt(
    modalityState: ModalityState? = null,
    handler: (T?, Throwable?) -> R
  ): CompletableFuture<R> =
    handleAsync(
      { result: T?, error: Throwable? -> handler(result, error?.let { extractError(it) }) },
      getEDTExecutor(modalityState)
    )

  private fun extractError(error: Throwable): Throwable {
    return when (error) {
      is CompletionException -> extractError(error.cause!!)
      is ExecutionException -> extractError(error.cause!!)
      else -> error
    }
  }

  private fun getEDTExecutor(modalityState: ModalityState? = null) = Executor { runnable ->
    runInEdt(modalityState) { runnable.run() }
  }
}
