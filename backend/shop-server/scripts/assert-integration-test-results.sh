#!/usr/bin/env bash

set -Eeuo pipefail

report_dir="${1:-target/failsafe-reports}"
expected_suites=(
  org.muybaby.shopserver.auth.token.RedisTokenStoreIntegrationTest
  org.muybaby.shopserver.customerservice.CustomerServiceImageThumbnailMySqlTest
  org.muybaby.shopserver.fulfillment.CommerceFulfillmentMySqlMigrationTest
  org.muybaby.shopserver.logistics.service.ShipmentWorkflowMySqlConcurrencyTest
  org.muybaby.shopserver.order.cleanup.OrderAggregateCleanupMySqlMigrationTest
  org.muybaby.shopserver.order.service.AppOrderServiceMySqlConcurrencyTest
  org.muybaby.shopserver.product.engagement.AppProductEngagementControllerTest
  org.muybaby.shopserver.storage.StorageAssetTimezoneTest
  org.muybaby.shopserver.user.address.service.AppAddressServiceMySqlConcurrencyTest
  org.muybaby.shopserver.wechat.servicecard.WechatServiceCardMySqlMigrationTest
)

if [[ ! -d "$report_dir" ]]; then
  printf '集成测试报告目录不存在：%s\n' "$report_dir" >&2
  exit 1
fi

for suite in "${expected_suites[@]}"; do
  report="$report_dir/TEST-${suite}.xml"
  if [[ ! -f "$report" ]]; then
    printf '集成测试未执行或未生成报告：%s\n' "$suite" >&2
    exit 1
  fi
done

total_tests=0
total_skipped=0
report_count=0
for report in "$report_dir"/TEST-*.xml; do
  [[ -f "$report" ]] || continue
  tests="$(sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p' "$report" | head -n 1)"
  skipped="$(sed -n 's/.* skipped="\([0-9][0-9]*\)".*/\1/p' "$report" | head -n 1)"
  if [[ ! "$tests" =~ ^[0-9]+$ ]] || [[ ! "$skipped" =~ ^[0-9]+$ ]]; then
    printf '无法解析集成测试报告：%s\n' "$report" >&2
    exit 1
  fi
  total_tests=$((total_tests + tests))
  total_skipped=$((total_skipped + skipped))
  report_count=$((report_count + 1))
done

if ((report_count == 0 || total_tests == 0)); then
  printf '集成测试门禁失败：没有实际执行任何测试。\n' >&2
  exit 1
fi
if ((total_skipped != 0)); then
  printf '集成测试门禁失败：%s 项测试被跳过。\n' "$total_skipped" >&2
  exit 1
fi

printf '集成测试门禁通过：%s 个测试套件，%s 项测试，0 项跳过。\n' \
  "$report_count" "$total_tests"
