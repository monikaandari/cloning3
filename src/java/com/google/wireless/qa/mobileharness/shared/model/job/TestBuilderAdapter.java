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

package com.google.wireless.qa.mobileharness.shared.model.job;

import com.google.wireless.qa.mobileharness.shared.model.job.TestInfo.Builder;

/**
 * DEPRECATED. DO NOT USE. This class is only for exposing the {@link TestInfo.Builder} for the
 * legacy {@link com.google.wireless.qa.mobileharness.shared.api.job.TestInfo}.
 */
@Deprecated
public final class TestBuilderAdapter {
  public static Builder newTestInfoBuilder() {
    return TestInfo.newBuilder();
  }

  private TestBuilderAdapter() {}
}