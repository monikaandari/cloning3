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

package com.google.devtools.deviceaction.common.utils;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.devtools.deviceaction.common.schemas.Command;
import com.google.devtools.deviceaction.framework.proto.ActionSpec;
import com.google.devtools.deviceaction.framework.proto.AndroidPhoneSpec;
import com.google.devtools.deviceaction.framework.proto.Binary;
import com.google.devtools.deviceaction.framework.proto.DeviceConfig;
import com.google.devtools.deviceaction.framework.proto.DeviceSpec;
import com.google.devtools.deviceaction.framework.proto.DeviceType;
import com.google.devtools.deviceaction.framework.proto.FileSpec;
import com.google.devtools.deviceaction.framework.proto.GCSFile;
import com.google.devtools.deviceaction.framework.proto.Nullary;
import com.google.devtools.deviceaction.framework.proto.Operand;
import com.google.devtools.deviceaction.framework.proto.Unary;
import com.google.devtools.deviceaction.framework.proto.action.InstallMainlineSpec;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ProtoHelperTest {

  private static final String ID_1 = "id1";
  private static final String ID_2 = "id2";
  private static final Operand DEVICE_1 =
      Operand.newBuilder().setDeviceType(DeviceType.ANDROID_PHONE).setUuid(ID_1).build();
  private static final Operand DEVICE_2 =
      Operand.newBuilder().setDeviceType(DeviceType.ANDROID_PHONE).setUuid(ID_2).build();
  private static final Nullary NULLARY = Nullary.getDefaultInstance();
  private static final Unary UNARY = Unary.newBuilder().setFirst(DEVICE_1).build();
  private static final Binary BINARY =
      Binary.newBuilder().setFirst(DEVICE_1).setSecond(DEVICE_2).build();
  public static final AndroidPhoneSpec GOOGLE_SPEC =
      AndroidPhoneSpec.newBuilder().setBrand("Google").build();
  private static final FileSpec GCS_FILE =
      FileSpec.newBuilder()
          .setTag("train_folder")
          .setGcsFile(
              GCSFile.newBuilder().setProject("fake project").setGsUri("gs://bucket/d1/o1").build())
          .build();
  private static final FileSpec LOCAL_FILE =
      FileSpec.newBuilder().setTag("mainline_modules").setLocalPath("/local/path/f1").build();
  private static final InstallMainlineSpec FROM_DEVICE_CONFIG =
      InstallMainlineSpec.newBuilder()
          .setCleanUpSessions(false)
          .addFiles(GCS_FILE)
          .setEnableRollback(true)
          .build();
  private static final InstallMainlineSpec FROM_CMD =
      InstallMainlineSpec.newBuilder()
          .setCleanUpSessions(true)
          .addFiles(LOCAL_FILE)
          .setDevKeySigned(true)
          .build();
  private static final DeviceConfig DEVICE_CONFIG =
      DeviceConfig.newBuilder()
          .setDeviceSpec(DeviceSpec.newBuilder().setAndroidPhoneSpec(GOOGLE_SPEC))
          .setExtension(InstallMainlineSpec.installMainlineSpec, FROM_DEVICE_CONFIG)
          .build();
  private static final Unary INSTALL_MAINLINE =
      Unary.newBuilder().setFirst(DEVICE_1).setExtension(InstallMainlineSpec.ext, FROM_CMD).build();

  @Test
  public void getFirst_hasValueForUnaryAndBinary() {
    assertThat(ProtoHelper.getFirst(ActionSpec.newBuilder().setNullary(NULLARY).build())).isEmpty();
    assertThat(ProtoHelper.getFirst(ActionSpec.newBuilder().setUnary(UNARY).build()))
        .hasValue(DEVICE_1);
    assertThat(ProtoHelper.getFirst(ActionSpec.newBuilder().setBinary(BINARY).build()))
        .hasValue(DEVICE_1);
  }

  @Test
  public void getSecond_hasValueForBinary() {
    assertThat(ProtoHelper.getSecond(ActionSpec.newBuilder().setNullary(NULLARY).build()))
        .isEmpty();
    assertThat(ProtoHelper.getSecond(ActionSpec.newBuilder().setUnary(UNARY).build())).isEmpty();
    assertThat(ProtoHelper.getSecond(ActionSpec.newBuilder().setBinary(BINARY).build()))
        .hasValue(DEVICE_2);
  }

  @Test
  public void mergeActionSpec_getMergedResult() throws Exception {
    ActionSpec actionSpec =
        ProtoHelper.mergeActionSpec(
            Command.INSTALL_MAINLINE,
            ActionSpec.newBuilder().setUnary(INSTALL_MAINLINE).build(),
            DEVICE_CONFIG);

    assertTrue(actionSpec.hasUnary());
    assertThat(actionSpec.getUnary().getFirst()).isEqualTo(DEVICE_1);
    assertTrue(actionSpec.getUnary().hasExtension(InstallMainlineSpec.ext));
    InstallMainlineSpec spec = actionSpec.getUnary().getExtension(InstallMainlineSpec.ext);
    assertTrue(spec.getCleanUpSessions());
    assertTrue(spec.getDevKeySigned());
    assertTrue(spec.getEnableRollback());
    assertThat(spec.getFilesList()).containsExactly(LOCAL_FILE, GCS_FILE);
  }
}