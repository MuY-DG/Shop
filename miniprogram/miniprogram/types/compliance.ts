export type LegalDocumentType =
  | "PRIVACY_POLICY"
  | "USER_AGREEMENT"
  | "AFTER_SALE_POLICY";

export interface LegalDocumentResponse {
  id: string;
  documentType: LegalDocumentType;
  version: string;
  title: string;
  content: string;
  contentSha256: string;
  status: "PUBLISHED";
  effectiveAt?: string;
  publishedAt?: string;
  updatedAt?: string;
}
export interface MerchantPublicationResponse {
  id: string;
  revisionNo: number;
  status: "PUBLISHED";
  legalName: string;
  entityType: string;
  unifiedSocialCreditCode: string;
  businessAddress: string;
  customerServicePhone: string;
  complaintPhone: string;
  businessLicenseUrl: string;
  foodQualificationType: string;
  foodQualificationNumber: string;
  foodQualificationUrl: string;
  foodQualificationValidFrom: string;
  foodQualificationValidUntil: string;
  publishedAt?: string;
  updatedAt?: string;
}
