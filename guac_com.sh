#!/bin/bash

# Get yesterday's date at 6:30 PM IST
YESTERDAY=$(date -d "yesterday 18:25" "+%Y-%m-%d %H:%M:%S")

# Timezone for IST
TZ_OFFSET="+0530"

# Add all changes
git add .

# Commit with backdated timestamp
GIT_AUTHOR_DATE="$YESTERDAY $TZ_OFFSET" \
GIT_COMMITTER_DATE="$YESTERDAY $TZ_OFFSET" \
git commit -m "Backdated commit (yesterday 6:30 PM IST)"

# Show latest commit details
git log -1 --pretty=fuller