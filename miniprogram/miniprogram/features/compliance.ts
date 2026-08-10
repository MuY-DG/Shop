import type {
  LegalDocumentResponse,
  LegalDocumentType,
  MerchantPublicationResponse
} from "../types/compliance";

const LEGAL_DOCUMENT_TYPES = Object.freeze([
  "PRIVACY_POLICY",
  "USER_AGREEMENT",
  "AFTER_SALE_POLICY"
] as const);

export const COMPLIANCE_ROUTES = Object.freeze({
  settings: "/pages/account/settings/settings",
  merchant: "/pages/compliance/merchant/merchant"
});

export interface LegalDocumentView extends LegalDocumentResponse {
  effectiveAtText: string;
  publishedAtText: string;
}
export interface MerchantPublicationView extends MerchantPublicationResponse {
  foodQualificationValidity: string;
  publishedAtText: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function positiveId(value: unknown): string {
  const source = typeof value === "number" ? String(value) : cleanText(value);
  return /^[1-9]\d*$/.test(source) ? source : "";
}

function positiveRevision(value: unknown): number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0
    ? value
    : 0;
}

function isoDate(value: unknown): string {
  const source = cleanText(value);
  const match = /^(\d{4})-(\d{2})-(\d{2})(?:T|$)/.exec(source);
  return match ? `${match[1]}.${match[2]}.${match[3]}` : "";
}

export function parseLegalDocumentType(
  value: unknown
): LegalDocumentType | undefined {
  const source = cleanText(value).toUpperCase();
  return (LEGAL_DOCUMENT_TYPES as readonly string[]).includes(source)
    ? source as LegalDocumentType
    : undefined;
}

export function legalDocumentTitle(type: LegalDocumentType): string {
  switch (type) {
    case "PRIVACY_POLICY":
      return "个人信息保护政策";
    case "USER_AGREEMENT":
      return "用户协议";
    case "AFTER_SALE_POLICY":
      return "售后服务政策";
  }
}

export function buildLegalDocumentUrl(type: LegalDocumentType): string {
  return `/pages/compliance/document/document?type=${type}`;
}

export function normalizeLegalDocument(
  value: unknown,
  expectedType: LegalDocumentType
): LegalDocumentResponse | undefined {
  if (!isRecord(value)) {
    return undefined;
  }
  const id = positiveId(value.id);
  const documentType = parseLegalDocumentType(value.documentType);
  const version = cleanText(value.version);
  const title = cleanText(value.title);
  const content = cleanText(value.content);
  const contentSha256 = cleanText(value.contentSha256).toLowerCase();
  if (
    !id
    || documentType !== expectedType
    || value.status !== "PUBLISHED"
    || !/^[0-9A-Za-z._-]{1,40}$/.test(version)
    || !title
    || !content
    || !/^[0-9a-f]{64}$/.test(contentSha256)
  ) {
    return undefined;
  }
  return {
    id,
    documentType,
    version,
    title,
    content,
    contentSha256,
    status: "PUBLISHED",
    effectiveAt: cleanText(value.effectiveAt) || undefined,
    publishedAt: cleanText(value.publishedAt) || undefined,
    updatedAt: cleanText(value.updatedAt) || undefined
  };
}

export function buildLegalDocumentView(
  value: unknown,
  expectedType: LegalDocumentType
): LegalDocumentView | undefined {
  const document = normalizeLegalDocument(value, expectedType);
  return document
    ? {
        ...document,
        effectiveAtText: isoDate(document.effectiveAt),
        publishedAtText: isoDate(document.publishedAt)
      }
    : undefined;
}

export function buildMerchantPublicationView(
  value: unknown
): MerchantPublicationView | undefined {
  if (!isRecord(value) || value.status !== "PUBLISHED") {
    return undefined;
  }
  const id = positiveId(value.id);
  const revisionNo = positiveRevision(value.revisionNo);
  const legalName = cleanText(value.legalName);
  const entityType = cleanText(value.entityType);
  const unifiedSocialCreditCode = cleanText(value.unifiedSocialCreditCode);
  const businessAddress = cleanText(value.businessAddress);
  const customerServicePhone = cleanText(value.customerServicePhone);
  const complaintPhone = cleanText(value.complaintPhone);
  const businessLicenseUrl = cleanText(value.businessLicenseUrl);
  const foodQualificationType = cleanText(value.foodQualificationType);
  const foodQualificationNumber = cleanText(value.foodQualificationNumber);
  const foodQualificationUrl = cleanText(value.foodQualificationUrl);
  const foodQualificationValidFrom = cleanText(value.foodQualificationValidFrom);
  const foodQualificationValidUntil = cleanText(value.foodQualificationValidUntil);
  const validFromText = isoDate(foodQualificationValidFrom);
  const validUntilText = isoDate(foodQualificationValidUntil);
  if (
    !id
    || !revisionNo
    || !legalName
    || !entityType
    || !/^[0-9A-Z]{18}$/.test(unifiedSocialCreditCode)
    || !businessAddress
    || !customerServicePhone
    || !complaintPhone
    || !businessLicenseUrl
    || !foodQualificationType
    || !foodQualificationNumber
    || !foodQualificationUrl
    || !validFromText
    || !validUntilText
  ) {
    return undefined;
  }
  return {
    id,
    revisionNo,
    status: "PUBLISHED",
    legalName,
    entityType,
    unifiedSocialCreditCode,
    businessAddress,
    customerServicePhone,
    complaintPhone,
    businessLicenseUrl,
    foodQualificationType,
    foodQualificationNumber,
    foodQualificationUrl,
    foodQualificationValidFrom,
    foodQualificationValidUntil,
    publishedAt: cleanText(value.publishedAt) || undefined,
    updatedAt: cleanText(value.updatedAt) || undefined,
    foodQualificationValidity: `${validFromText} 至 ${validUntilText}`,
    publishedAtText: isoDate(value.publishedAt)
  };
}
