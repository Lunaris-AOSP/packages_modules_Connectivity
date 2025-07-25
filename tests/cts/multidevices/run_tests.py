#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

"""Main entrypoint for all of test cases."""

import sys
from connectivity_multi_devices_test import ConnectivityMultiDevicesTest
from mobly import suite_runner


if __name__ == "__main__":
  # For MoblyBinaryHostTest, this entry point will be called twice:
  # 1. List tests.
  #   <mobly-par-file-name> -- --list_tests
  # 2. Run tests.
  #   <mobly-par-file-name> -- --config=<yaml-path> \
  #      --device_serial=<device-serial> --log_path=<log-path>
  # Strip the "--" since suite runner doesn't recognize it.
  # While the parameters before "--" is for the infrastructure,
  # ignore them if any. Also, do not alter parameters if there
  # is no "--", in case the binary is invoked manually.
  if "--" in sys.argv:
    index = sys.argv.index("--")
    sys.argv = sys.argv[:1] + sys.argv[index + 1 :]
  # TODO: make the tests can be executed without manually list classes.
  suite_runner.run_suite([ConnectivityMultiDevicesTest], sys.argv)
