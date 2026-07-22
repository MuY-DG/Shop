const CONTACT_PHONE_PATTERN = /^[0-9+()\-\s]{5,32}$/;

export function normalizeContactPhone(value: unknown): string {
  if (typeof value !== "string") {
    return "";
  }
  const phone = value.trim().replace(/\s+/g, " ");
  return CONTACT_PHONE_PATTERN.test(phone) ? phone : "";
}
