# Copyright 2022 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

licenses(["notice"])

exports_files(["LICENSE"])

package_group(
    name = "deviceinfra_api_users",
    includes = [
        ":deviceinfra_all_pkg",
    ],
)

package_group(
    name = "deviceinfra_all_pkg",
    includes = [
        ":deviceinfra_pkg",
    ],
)

package_group(
    name = "deviceinfra_pkg",
    packages = [
        "//...",
    ],
)

package_group(
    name = "deviceinfra_metrics_pkg",
    packages = [
        "//src/java/com/google/devtools/common/metrics/stability/...",
        "//src/javatests/com/google/devtools/common/metrics/stability/...",
    ],
)
