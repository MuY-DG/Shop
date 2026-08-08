import type { AddressResponse } from "../../../types/checkout";

export interface OrderAddressOptionView extends AddressResponse {
  detailDisplay: string;
  phoneDisplay: string;
  selected: boolean;
}

interface ReceiverSnapshot {
  receiverName: string;
  receiverPhone: string;
  receiverAddress: string;
}

export interface OrderReceiverView {
  receiverName: string;
  receiverPhone: string;
  receiverRegion: string;
  receiverDetailAddress: string;
}

function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function compactAddress(value: unknown): string {
  return cleanText(value).replace(/\s+/g, "");
}

function addressDetail(address: AddressResponse): string {
  let detail = cleanText(address.detailAddress).replace(/\s+/g, " ");
  const locationName = cleanText(address.locationName).replace(/\s+/g, " ");
  const doorplate = cleanText(address.doorplate).replace(/\s+/g, " ");
  if (locationName && !detail.includes(locationName)) {
    detail = [detail, locationName].filter(Boolean).join(" ");
  }
  if (doorplate && !detail.endsWith(doorplate)) {
    detail = [detail, doorplate].filter(Boolean).join(" ");
  }
  return detail;
}

function findMatchingAddress(
  receiverAddress: unknown,
  addresses: readonly OrderAddressOptionView[]
): OrderAddressOptionView | undefined {
  const currentAddress = compactAddress(receiverAddress);
  return addresses.find((address) => {
    const composedAddress = [
      address.province,
      address.city,
      address.district,
      addressDetail(address)
    ].map(cleanText).join("");
    return currentAddress === compactAddress(address.formattedAddress)
      || currentAddress === compactAddress(composedAddress);
  });
}

function addressRegion(address: AddressResponse): string {
  const parts: string[] = [];
  [address.province, address.city, address.district]
    .map(cleanText)
    .filter(Boolean)
    .forEach((part) => {
      if (parts[parts.length - 1] !== part) {
        parts.push(part);
      }
    });
  return parts.join(" ");
}

function splitReceiverAddress(value: unknown): Pick<
  OrderReceiverView,
  "receiverRegion" | "receiverDetailAddress"
> {
  let remaining = cleanText(value);
  const regionParts: string[] = [];
  const takeRegionPart = (pattern: RegExp) => {
    const match = remaining.match(pattern);
    if (match?.[1]) {
      regionParts.push(match[1]);
      remaining = remaining.slice(match[1].length).trim();
    }
  };

  takeRegionPart(/^(北京市|天津市|上海市|重庆市|.+?(?:特别行政区|自治区|省))/);
  takeRegionPart(/^(.+?(?:自治州|地区|盟|市))/);
  takeRegionPart(/^(.+?(?:自治县|区|县|旗|市))/);

  return {
    receiverRegion: regionParts.join(" "),
    receiverDetailAddress: remaining || cleanText(value)
  };
}

export function buildSelectedReceiverView(
  address: OrderAddressOptionView
): OrderReceiverView {
  return {
    receiverName: cleanText(address.receiverName),
    receiverPhone: cleanText(address.receiverPhone),
    receiverRegion: addressRegion(address),
    receiverDetailAddress: addressDetail(address) || address.detailDisplay
  };
}

export function buildCurrentReceiverView(
  receiver: ReceiverSnapshot,
  addresses: readonly OrderAddressOptionView[]
): OrderReceiverView {
  const matchedAddress = findMatchingAddress(receiver.receiverAddress, addresses);
  const addressView = matchedAddress
    ? {
        receiverRegion: addressRegion(matchedAddress),
        receiverDetailAddress: addressDetail(matchedAddress)
          || matchedAddress.detailDisplay
      }
    : splitReceiverAddress(receiver.receiverAddress);

  return {
    receiverName: cleanText(receiver.receiverName),
    receiverPhone: cleanText(receiver.receiverPhone),
    ...addressView
  };
}

export function resolveCurrentOrderAddressId(
  receiver: Pick<ReceiverSnapshot, "receiverAddress">,
  addresses: readonly OrderAddressOptionView[]
): string {
  return findMatchingAddress(receiver.receiverAddress, addresses)?.id || "";
}

export function parseModifyOrderId(value: unknown): number {
  if (typeof value === "string" && !/^\d+$/.test(value.trim())) {
    return 0;
  }
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0;
}

export function canModifyOrderReceiver(status: unknown): boolean {
  return status === "CREATED" || status === "PAYING" || status === "PAID";
}

export function normalizeSelectedAddressId(value: unknown): string {
  const normalized = cleanText(value);
  return /^\d+$/.test(normalized) ? normalized : "";
}

export function maskReceiverPhone(value: unknown): string {
  const normalized = cleanText(value);
  return /^\d{11}$/.test(normalized)
    ? `${normalized.slice(0, 3)}****${normalized.slice(-4)}`
    : normalized;
}

export function buildOrderAddressOptions(
  addresses: readonly AddressResponse[],
  selectedAddressId: string
): OrderAddressOptionView[] {
  const selectedId = normalizeSelectedAddressId(selectedAddressId);
  return (Array.isArray(addresses) ? addresses : [])
    .filter((address) => Boolean(normalizeSelectedAddressId(address.id)))
    .map((address) => ({
      ...address,
      detailDisplay: cleanText(address.formattedAddress) || [
        address.province,
        address.city,
        address.district,
        addressDetail(address)
      ].map(cleanText).filter(Boolean).join(" "),
      phoneDisplay: maskReceiverPhone(address.receiverPhone),
      selected: address.id === selectedId
    }));
}
