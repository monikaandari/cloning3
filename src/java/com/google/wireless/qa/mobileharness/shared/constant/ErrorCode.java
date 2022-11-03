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

package com.google.wireless.qa.mobileharness.shared.constant;

import java.util.HashMap;
import java.util.Map;

/** Mobile Harness error code. */
public enum ErrorCode {
  UNKNOWN(1),
  LEGACY_ERROR(2),
  INTERRUPTED(3),
  VERSION_FORMAT_ERROR(4),
  ILLEGAL_ARGUMENT(5),
  ACTION_ABORT(6),
  URI_ERROR(7),
  HTTP_ERROR(8),
  PERMISSION_ERROR(9),
  NEXT_GEN_ERROR(10),

  SPONGE_ERROR(11),
  ENCODING_ERROR(12),
  DECODING_ERROR(13),
  PLUGIN_ERROR(14),
  SERVER_NOT_ACCESSIBLE(15),

  PROCESS_ERROR(22),
  STUB_VERSION_TOO_LOW(23),
  SERVICE_VERSION_TOO_LOW(24),
  NOT_IMPLEMENTED(25),
  RACE_CONDITION(26),
  NETWORK_ERROR(27),
  ILLEGAL_STATE(28),
  SEARCH_ERROR(29),
  BUILD_ERROR(30),
  MAIL_ERROR(31),
  NUMBER_FORMAT_ERROR(32),

  SERIALIZE_ERROR(34),
  DESERIALIZE_ERROR(35),
  THREAD_POOL_ERROR(36),
  DEPRECATED(37),

  STORAGE_ERROR(39),
  VALIDATOR_METHOD_ERROR(40),
  DREMEL_ERROR(41),
  SET_ERRORTYPE_ERROR(42),

  RPC_ERROR(43),

  DATEFORMAT_PARSE_ERROR(44),

  CDPUSH(45),
  CHECKSUM_ERROR(46),
  PROTOBUF_ERROR(47),

  // JOB ERROR: 200 ~ 399
  JOB_NOT_FOUND(201),
  JOB_DUPLICATED(202),
  JOB_TIMEOUT(203),
  JOB_UNKNOWN_EXEC_MODE(205),
  JOB_TYPE_NOT_SUPPORTED(206),
  JOB_DEPRECATED_FLAG(207),
  JOB_PARAM_ERROR(209),
  JOB_FILE_ERROR(210),
  JOB_FAILED_TO_START(211),
  JOB_SPEC_ERROR(212),
  JOB_NOT_STARTED(213),
  JOB_CONFIG_ERROR(214),
  JOB_EXEC_ERROR(215),
  JOB_TEAR_DOWN_ERROR(216),
  JOB_EVENT_ERROR(217),
  JOB_FILE_CLEANUP_ERROR(218),
  JOB_INTERRUPTED(219),
  JOB_VALIDATE_ERROR(220),
  JOB_TAG_ANNOTATION_INVALID(221),
  JOB_SPEC_PARSE_ERROR(222),
  JOB_PLUGIN_UNKNOWN_ERROR(223),
  JOB_INFO_ERROR(224),

  // TEST ERROR: 400 ~ 599
  TEST_NOT_FOUND(401),
  TEST_DUPLICATED(402),
  TEST_FAILED_TO_REMOVE(403),
  TEST_FAILED(404),
  TEST_FINISHED_UNEXPECTEDLY(405),
  TEST_ILLEGAL_RESULT(406),
  TEST_ILLEGAL_STATUS(407),
  TEST_NOT_ASSIGNED(408),
  TEST_DUPLICATED_ALLOCATION(409),
  TEST_RESULT_NOT_FOUND(410),
  TEST_ERROR(411),
  TEST_POST_RUN_ERROR(412),
  TEST_INTERRUPTED(413),
  TEST_ABORTED(415),
  TEST_FAILED_TO_START(416),
  TEST_CRASHED(417),
  TEST_TIMEOUT(418),
  TEST_LISTER_ERROR(419),
  TEST_SIGNAL_ERROR(420),
  TEST_NOT_STARTED(421),
  TEST_MESSAGE_ERROR(422),
  TEST_PREPARE_ERROR(423),
  TEST_PLUGIN_UNKNOWN_ERROR(424),
  TEST_INFRA_ERROR(425),

  TEST_XML_ERROR(429),

  // LAB ERROR: 600 ~ 799
  LAB_NOT_ACCESSIBLE(600),
  LAB_NOT_FOUND(601),
  LAB_EXPIRED(602),
  LAB_NOT_EMPTY(603),
  LAB_METRIC_NOT_FOUND(604),
  LAB_MONITORING_ERROR(605),
  LAB_CONFIG_ERROR(606),

  // DEVICE ERROR: 800 ~ 999
  DEVICE_NOT_FOUND(801),
  DEVICE_NOT_READY(802),
  DEVICE_BUSY(803),
  DEVICE_DUPLICATED(805),
  DEVICE_PROPERTY_FORMAT_ERROR(806),
  DEVICE_NOT_SUPPORTED(807),
  DEVICE_CONFIG_ERROR(808),
  DEVICE_USB_ERROR(809),
  DEVICE_ALLOCATOR_ERROR(810),
  DEVICE_STATUS_UNKNOWN(811),
  DEVICE_ENVIRONMENT_VALIDATE_ERROR(812),
  DEVICE_DUPLICATED_ALLOCATION(813),

  INSTALLATION_ERROR(1000),
  INSTALLATION_GMS_DOWNGRADE(1001),
  INSTALLATION_DEVICE_TOO_OLD(1002),
  INSTALLATION_GMS_INCOMPATIBLE(1003),
  INSTALLATION_ABI_INCOMPATIBLE(1004),
  INSTALLATION_UPDATE_INCOMPATIBLE(1005),
  INSTALLATION_MISSING_SHARED_LIBRARY(1007),
  INSTALLATION_APP_BLACKLISTED(1008),

  // ***********************************************************************************************
  // Mobile Harness infrastructure: 6000 ~ 7999
  // ***********************************************************************************************

  // REFLECTION ERROR: 6000 ~ 6099
  REFLECTION_OPERATION_ERROR(6000),
  REFLECTION_CLASS_NOT_FOUND(6001),
  REFLECTION_CLASS_TYPE_NOT_MATCHED(6002),
  REFLECTION_CONSTRUCTOR_NOT_FOUND(6003),
  REFLECTION_INSTANTIATION_ERROR(6004),
  REFLECTION_ACCESS_ERROR(6005),

  // EVENT ERROR: 6300 ~ 6399
  EVENT_NOT_POSTED(6301),
  EVENT_HANDLER_ERROR(6302),
  EVENT_SCOPE_NOT_FOUND(6303),
  EVENT_NOT_SUPPORTED(6304),
  EVENT_SCOPE_INIT_ERROR(6305),

  // ***********************************************************************************************
  // End. You should double check whether your error codes can fit into the above ranges before
  // adding error codes >= 20,000.
  // ***********************************************************************************************

  END_OF_ERROR_CODE(20_000);

  private final int code;

  ErrorCode(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }

  private static final Map<Integer, ErrorCode> intToEnum = new HashMap<>();

  static {
    for (ErrorCode errorCode : ErrorCode.values()) {
      intToEnum.put(errorCode.code(), errorCode);
    }
  }

  public static ErrorCode enumOf(int code) {
    ErrorCode result = intToEnum.get(code);
    return result == null ? ErrorCode.UNKNOWN : result;
  }
}
