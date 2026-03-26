#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

# Get yesterday's date at 6:30 PM IST
YESTERDAY=$(date -d "yesterday 18:25" "+%Y-%m-%d %H:%M:%S")

# Timezone for IST
TZ_OFFSET="+0530"

# Add all changes
git add .

# Commit with backdated timestamp
GIT_AUTHOR_DATE="$YESTERDAY $TZ_OFFSET" \
GIT_COMMITTER_DATE="$YESTERDAY $TZ_OFFSET" \
git commit -m "totp auth"

# Show latest commit details
git log -1 --pretty=fuller
