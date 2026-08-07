import type { AddressResponse } from "../../../types/checkout";

export interface OrderAddressOptionView extends AddressResponse {
  detailDisplay: string;
  phoneDisplay: string;
  selected: boolean;
}

function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
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
        address.detailAddress
      ].map(cleanText).filter(Boolean).join(" "),
      phoneDisplay: maskReceiverPhone(address.receiverPhone),
      selected: address.id === selectedId
    }));
}
