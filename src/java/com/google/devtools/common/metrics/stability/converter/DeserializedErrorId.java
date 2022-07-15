/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.common.metrics.stability.converter;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.devtools.common.metrics.stability.model.ErrorId;
import com.google.devtools.common.metrics.stability.model.proto.ErrorIdProto;
import com.google.devtools.common.metrics.stability.util.ErrorIdFormatter;

/** ErrorId deserialized from a proto (usually from another process). */
@AutoValue
public abstract class DeserializedErrorId implements ErrorId {

  /** Do not make it public. */
  static DeserializedErrorId of(ErrorIdProto.ErrorId errorIdProto) {
    // TODO(b/158161092): Add the namespace.
    return new AutoValue_DeserializedErrorId(
        errorIdProto.getCode(),
        errorIdProto.getName(),
        errorIdProto.getType(),
        errorIdProto.getNamespace());
  }

  @Override
  @Memoized
  public String toString() {
    return ErrorIdFormatter.formatErrorId(this);
  }
}
