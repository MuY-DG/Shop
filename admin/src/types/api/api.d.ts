/**
 * API 接口类型定义模块
 *
 * 提供所有后端接口的类型定义
 *
 * ## 主要功能
 *
 * - 通用类型（分页参数、响应结构等）
 * - 认证类型（登录、用户信息等）
 * - 系统管理类型（用户、角色等）
 * - 全局命名空间声明
 *
 * ## 使用场景
 *
 * - API 请求参数类型约束
 * - API 响应数据类型定义
 * - 接口文档类型同步
 *
 * ## 注意事项
 *
 * - 在 .vue 文件使用需要在 eslint.config.mjs 中配置 globals: { Api: 'readonly' }
 * - 使用全局命名空间，无需导入即可使用
 *
 * ## 使用方式
 *
 * ```typescript
 * const params: Api.Auth.LoginParams = { userName: 'admin', password: '123456' }
 * const response: Api.Auth.UserInfo = await fetchUserInfo()
 * ```
 *
 * @module types/api/api
 * @author Art Design Pro Team
 */

declare namespace Api {
  /** 通用类型 */
  namespace Common {
    /** 分页参数 */
    interface PaginationParams {
      /** 当前页码 */
      current: number
      /** 每页条数 */
      size: number
      /** 总条数 */
      total: number
    }

    /** 通用搜索参数 */
    type CommonSearchParams = Pick<PaginationParams, 'current' | 'size'>

    /** 分页响应基础结构 */
    interface PaginatedResponse<T = any> {
      records: T[]
      current: number
      size: number
      total: number
    }

    /** 启用状态 */
    type EnableStatus = '1' | '2'

    interface AssetValue {
      fileId: number | null
      url: string
    }
  }

  /** 认证类型 */
  namespace Auth {
    /** 登录参数 */
    interface LoginParams {
      userName: string
      password: string
    }

    /** 登录响应 */
    interface LoginResponse {
      token: string
      refreshToken: string
      expiresIn: number
    }

    /** 用户信息 */
    interface UserInfo {
      buttons: string[]
      roles: string[]
      userId: number
      userName: string
      email: string
      avatar?: string
    }

    /** 管理员登录设备会话 */
    interface AdminSession {
      sessionId: string
      deviceName: string
      browser: string
      os: string
      ipAddress: string
      userAgent: string
      loginAt: string
      lastSeenAt: string
      current: boolean
    }
  }

  /** 系统管理类型 */
  namespace SystemManage {
    type AdminUserStatus = 'ENABLED' | 'DISABLED'

    /** 用户列表 */
    type UserList = Api.Common.PaginatedResponse<UserListItem>

    /** 用户列表项 */
    interface UserListItem {
      id: number
      username: string
      displayName: string
      email: string
      avatar: string
      status: string
      /** 0 不限制、1 单设备、N 为最多同时登录 N 台设备。 */
      maxSessions: number
      roleIds: number[]
      roleCodes: string[]
      lastLoginAt?: string | null
      createdAt: string
      updatedAt: string
      /** Legacy template fields retained for the example table pages. */
      userName?: string
      userGender?: string
      nickName?: string
      userPhone?: string
      userEmail?: string
      userRoles?: string[]
      createBy?: string
      createTime?: string
      updateBy?: string
      updateTime?: string
    }

    /** 用户搜索参数 */
    type UserSearchParams = Partial<
      Pick<
        UserListItem,
        'username' | 'email' | 'status' | 'userName' | 'userGender' | 'userPhone' | 'userEmail'
      > &
        Api.Common.CommonSearchParams
    >

    interface UserCreateForm {
      username: string
      displayName: string
      email: string
      password: string
      avatar: string
      maxSessions: number
      roleIds: number[]
    }

    interface UserUpdateForm {
      displayName: string
      email: string
      password?: string
      avatar: string
      status: AdminUserStatus
      maxSessions: number
      roleIds: number[]
    }

    /** 角色列表 */
    type RoleList = Api.Common.PaginatedResponse<RoleListItem>

    /** 角色列表项 */
    interface RoleListItem {
      id: number
      name: string
      code: string
      description: string
      enabled: boolean
      createdAt: string
      updatedAt: string
    }

    /** 角色搜索参数 */
    type RoleSearchParams = Partial<
      Pick<RoleListItem, 'name' | 'code' | 'enabled'> &
        Api.Common.CommonSearchParams & {
          startTime: string | null
          endTime: string | null
        }
    >

    interface RoleForm {
      name: string
      code: string
      description: string
      enabled: boolean
    }

    interface RoleGrants {
      roleId: number
      menuIds: number[]
      permissionIds: number[]
    }

    interface RoleGrantForm {
      menuIds: number[]
      permissionIds: number[]
    }
  }

  namespace Product {
    type ProductStatus = 'DRAFT' | 'ON_SALE' | 'OFF_SALE'
    type CategoryStatus = 'ENABLED' | 'DISABLED'
    type SkuStatus = 'ENABLED' | 'DISABLED'
    type SpecType = 'SINGLE' | 'MULTI'
    type ProductParameterValueType =
      | 'TEXT'
      | 'NUMBER'
      | 'SINGLE_SELECT'
      | 'MULTI_SELECT'
      | 'BOOLEAN'
    type ProductParameterStatus = 'ENABLED' | 'DISABLED'
    type ProductBadgeTone = 'RED' | 'ORANGE' | 'GREEN' | 'NEUTRAL'
    type ProductParameterCardRole = 'HIGHLIGHT' | 'META'
    type ProductParameterCardRenderer = 'TEXT' | 'PILL' | 'LEVEL' | 'SPICE'
    type FreightChargeMode = 'FREE' | 'FIXED'
    type FreightTemplateStatus = 'ENABLED' | 'DISABLED'
    type ProductReviewStatus = 'PUBLISHED' | 'HIDDEN'

    interface Category {
      id: number
      parentId: number
      name: string
      icon: string
      iconFileId?: number | null
      sortOrder: number
      status: CategoryStatus
      children: Category[]
    }

    interface CategoryForm {
      parentId: number
      name: string
      icon: string
      iconFileId?: number | null
      sortOrder: number
      status: CategoryStatus
    }

    interface CategoryPositionForm {
      parentId: number
      index: number
    }

    type SpuList = Api.Common.PaginatedResponse<SpuListItem>

    interface SpuListItem {
      id: number
      categoryId: number
      categoryName: string
      title: string
      subtitle: string
      mainImage: string
      mainImageFileId?: number | null
      status: ProductStatus
      sortOrder: number
      minPriceCent?: number | null
      maxPriceCent?: number | null
      totalStock: number
      skuCount: number
      actualSales: number
      virtualSales: number
      displaySales: number
      createdAt: string
      updatedAt: string
      expiresAt?: string | null
      deletedAt?: string | null
    }

    type SpuSearchParams = Partial<
      Api.Common.CommonSearchParams & {
        categoryId: number
        title: string
        status: ProductStatus
        recycled: boolean
      }
    >

    interface SpuPurgeForm {
      confirmationTitle: string
    }

    interface ProductImage {
      id: number
      url: string
      fileId?: number | null
      sortOrder: number
    }

    interface ProductImageForm {
      url: string
      fileId?: number | null
    }

    interface Sku {
      id?: number
      skuCode: string
      specJson: string
      specText: string
      priceCent: number
      originalPriceCent?: number | null
      costPriceCent?: number | null
      stockAvailable: number
      lowStockThreshold: number
      weightGram?: number | null
      volumeCubicMeter?: number | null
      image: string
      imageFileId?: number | null
      status: SkuStatus
      defaultSelected: boolean
      combinationKey: string
      specValueKeys: string[]
      wholesaleTiers: WholesaleTier[]
      sortOrder: number
    }

    interface WholesaleTier {
      minQuantity: number
      unitPriceCent: number
    }

    interface SpecValue {
      id?: number
      valueKey: string
      valueName: string
      image: string
      imageFileId?: number | null
      sortOrder: number
    }

    interface SpecValueForm {
      id?: number
      valueKey: string
      valueName: string
      image: string
      imageFileId?: number | null
      sortOrder: number
    }

    interface SpecGroup {
      id?: number
      groupKey: string
      name: string
      imageEnabled: boolean
      sortOrder: number
      values: SpecValue[]
    }

    interface SpecGroupForm {
      id?: number
      groupKey: string
      name: string
      imageEnabled: boolean
      sortOrder: number
      values: SpecValueForm[]
    }

    interface SpuDetail {
      id: number
      categoryId: number
      categoryName: string
      title: string
      subtitle: string
      mainImage: string
      mainImageFileId?: number | null
      mainVideo: string
      mainVideoFileId?: number | null
      specType: SpecType
      freightTemplateId: number
      virtualSales: number
      sellingPoints: string
      detailHtml: string
      displayBadgeText: string
      displayBadgeTone: ProductBadgeTone
      sortOrder: number
      status: ProductStatus
      images: ProductImage[]
      skus: Sku[]
      specGroups: SpecGroup[]
      guaranteeServiceIds: number[]
      couponTemplateIds: number[]
      createdAt: string
      updatedAt: string
    }

    interface SpuForm {
      categoryId: number
      title: string
      subtitle: string
      mainImage: string
      mainImageFileId?: number | null
      mainVideo: string
      mainVideoFileId?: number | null
      specType: SpecType
      freightTemplateId: number
      virtualSales: number
      sellingPoints: string
      detailHtml: string
      displayBadgeText: string
      displayBadgeTone: ProductBadgeTone
      sortOrder: number
      images: ProductImageForm[]
      skus: Sku[]
      specGroups: SpecGroupForm[]
      guaranteeServiceIds: number[]
    }

    interface ProductParameterOption {
      id?: number
      optionCode: string
      optionLabel: string
      displayLevel?: number | null
      sortOrder: number
    }

    interface ProductParameterDefinition {
      id: number
      parameterCode: string
      parameterName: string
      valueType: ProductParameterValueType
      unit: string
      description: string
      required: boolean
      filterable: boolean
      cardVisible: boolean
      detailVisible: boolean
      cardRole: ProductParameterCardRole
      cardRenderer: ProductParameterCardRenderer
      cardPriority: number
      sortOrder: number
      status: ProductParameterStatus
      categoryIds: number[]
      options: ProductParameterOption[]
      createdAt: string
      updatedAt: string
    }

    interface ProductParameterDefinitionForm {
      parameterCode: string
      parameterName: string
      valueType: ProductParameterValueType
      unit: string
      description: string
      required: boolean
      filterable: boolean
      cardVisible: boolean
      detailVisible: boolean
      cardRole: ProductParameterCardRole
      cardRenderer: ProductParameterCardRenderer
      cardPriority: number
      sortOrder: number
      status: ProductParameterStatus
      categoryIds: number[]
      options: ProductParameterOption[]
    }

    interface SpuParameterValue {
      parameterId: number
      textValue?: string | null
      numberValue?: number | null
      booleanValue?: boolean | null
      optionCodes: string[]
      displayText?: string
    }

    interface SpuParameterValuesForm {
      values: SpuParameterValue[]
    }

    interface StockAdjustmentForm {
      quantityDelta: number
      reason: string
    }

    interface SpecTemplateValue {
      id?: number
      valueKey: string
      valueName: string
      sortOrder: number
    }

    interface SpecTemplateGroup {
      id?: number
      groupKey: string
      name: string
      imageEnabled: boolean
      sortOrder: number
      values: SpecTemplateValue[]
    }

    interface SpecTemplateSummary {
      id: number
      name: string
      groupCount: number
      valueCount: number
      createdAt: string
      updatedAt: string
    }

    interface SpecTemplateDetail {
      id: number
      name: string
      groups: SpecTemplateGroup[]
      createdAt: string
      updatedAt: string
    }

    interface SpecTemplateForm {
      name: string
      groups: SpecTemplateGroup[]
    }

    interface SpecTemplateSaveForm {
      name: string
    }

    type GuaranteeServiceList = Api.Common.PaginatedResponse<GuaranteeService>

    interface GuaranteeService {
      id: number
      termsName: string
      contentDescription: string
      icon: string
      iconFileId?: number | null
      sortOrder: number
      visible: boolean
      createdAt: string
      updatedAt: string
    }

    type GuaranteeServiceSearchParams = Partial<
      Api.Common.CommonSearchParams & {
        name: string
        visible: boolean
      }
    >

    interface GuaranteeServiceForm {
      termsName: string
      contentDescription: string
      icon: string
      iconFileId?: number | null
      sortOrder: number
      visible: boolean
    }

    interface GuaranteeServiceVisibilityForm {
      visible: boolean
    }

    type ProductReviewList = Api.Common.PaginatedResponse<ProductReview>

    interface ProductReview {
      id: number
      spuId: number
      productTitle: string
      productImage: string
      userId: number
      reviewerName: string
      orderId: number
      orderNo: string
      orderItemId: number
      specText: string
      rating: number
      content: string
      anonymous: boolean
      status: ProductReviewStatus
      createdAt: string
      updatedAt: string
      moderatedByAdminUserId?: number | null
      moderatedAt?: string | null
    }

    type ProductReviewSearchParams = Partial<
      Api.Common.CommonSearchParams & {
        spuId: number
        productTitle: string
        rating: number
        status: ProductReviewStatus
        anonymous: boolean
      }
    >

    interface ProductReviewStatusForm {
      status: ProductReviewStatus
    }

    interface FreightTemplate {
      id: number
      name: string
      chargeMode: FreightChargeMode
      fixedAmountCent?: number | null
      status: FreightTemplateStatus
      sortOrder: number
      createdAt: string
      updatedAt: string
    }

    interface FreightTemplateForm {
      name: string
      chargeMode: FreightChargeMode
      fixedAmountCent?: number | null
      status: FreightTemplateStatus
      sortOrder: number
    }

    interface ProductCouponBindingForm {
      couponTemplateIds: number[]
    }

    interface ProductCouponCreateForm {
      name: string
      description?: string
      couponType: Api.Marketing.CouponType
      discountType: Api.Marketing.DiscountType
      thresholdCent: number
      discountCent: number
      scopeType?: 'PRODUCT'
      scopeValue?: string
      strategyKey?: string
      totalStock: number
      perUserLimit: number
      validStartAt: string
      validEndAt: string
      status: Api.Marketing.CouponTemplateStatus
      sortOrder?: number
    }
  }

  namespace Storage {
    type Provider = 'LOCAL' | 'TENCENT_COS'

    interface Config {
      provider: Provider
      persisted: boolean
      defaultProvider: Provider
      publicBaseUrl: string
      localPublicBaseUrl: string
      cosPublicBaseUrl: string
      localRoot: string
      cosRegion: string
      cosBucket: string
      cosSecretIdMasked: string
      cosSecretKeyConfigured: boolean
    }

    interface ConfigForm {
      provider: Provider
      publicBaseUrl?: string
      localPublicBaseUrl: string
      cosPublicBaseUrl: string
      localRoot: string
      cosRegion: string
      cosBucket: string
      cosSecretId?: string
      cosSecretKey?: string
    }

    type AssetScope = 'LIBRARY' | 'ATTACHMENT' | 'SECRET'
    type MediaKind = 'IMAGE' | 'VIDEO' | 'DOCUMENT'
    type Visibility = 'PUBLIC' | 'PRIVATE'
    type AssetStatus = 'ACTIVE' | 'DELETE_PENDING' | 'DELETED'
    type UploadedByType = 'ADMIN' | 'APP'
    type FolderStatus = 'ENABLED' | 'DISABLED'
    type ReferenceStatus = 'REFERENCED' | 'UNREFERENCED'
    type UsageStatus = 'ACTIVE' | 'REMOVED'

    type UsageType =
      | 'PRODUCT_CATEGORY_ICON'
      | 'PRODUCT_SPU_MAIN'
      | 'PRODUCT_SPU_GALLERY'
      | 'PRODUCT_SKU_IMAGE'
      | 'PRODUCT_SPU_VIDEO'
      | 'PRODUCT_SPEC_VALUE_IMAGE'
      | 'GUARANTEE_SERVICE_ICON'
      | 'RICH_TEXT_IMAGE'
      | 'PRODUCT_DETAIL_HTML'
      | 'HOME_BANNER'
      | 'HOME_CATEGORY_IMAGE'
      | 'HOME_PRODUCT_IMAGE'
      | 'ORDER_ITEM_SNAPSHOT'
      | 'AFTER_SALE_EVIDENCE'
      | 'PAYMENT_CONFIG_CERT'

    type UsageOwnerType =
      | 'PRODUCT_CATEGORY'
      | 'PRODUCT_SPU'
      | 'PRODUCT_SKU'
      | 'PRODUCT_SPEC_VALUE'
      | 'GUARANTEE_SERVICE'
      | 'HOME_BANNER'
      | 'HOME_CATEGORY_ITEM'
      | 'HOME_PRODUCT_ITEM'
      | 'ORDER_ITEM'
      | 'AFTER_SALE'
      | 'PAYMENT_CONFIG'

    type AssetList = Api.Common.PaginatedResponse<Asset>
    type AssetUsageList = Api.Common.PaginatedResponse<AssetUsage>

    interface AssetQueryParams extends Partial<Api.Common.CommonSearchParams> {
      keyword?: string
      mediaKind?: Exclude<MediaKind, 'DOCUMENT'>
      /** 0 means ungrouped; omitted means all folders. */
      folderId?: number
      referenceStatus?: ReferenceStatus
      createdFrom?: string
      createdTo?: string
    }

    interface AssetUsageQueryParams extends Partial<Api.Common.CommonSearchParams> {
      status?: UsageStatus
    }

    interface Asset {
      id: number
      scope: AssetScope
      mediaKind: MediaKind
      folderId?: number | null
      visibility: Visibility
      provider: string
      originalFilename: string
      contentType: string
      extension: string
      sizeBytes: number
      sha256?: string
      width?: number | null
      height?: number | null
      durationSeconds?: number | null
      altText?: string | null
      tags?: string[]
      status: AssetStatus
      uploadedByType: UploadedByType
      uploadedById?: string | null
      url?: string | null
      publicUrl?: string | null
      usageCount: number
      createdAt: string
      updatedAt: string
      deletedAt?: string | null
      usages?: AssetUsage[]
    }

    type AssetItem = Asset

    interface AssetUsage {
      id: number
      assetId: number
      usageType: UsageType | string
      ownerType: UsageOwnerType | string
      ownerId?: number | null
      ownerLabel: string
      snapshotUrl?: string | null
      sortOrder?: number | null
      protected: boolean
      status: UsageStatus
      createdAt: string
      updatedAt: string
    }

    interface AssetFolder {
      id: number
      parentId: number
      name: string
      sortOrder: number
      status: FolderStatus
      createdAt: string
      updatedAt: string
      children: AssetFolder[]
    }

    interface AssetFolderForm {
      parentId: number
      name: string
      sortOrder: number
      status: FolderStatus
    }

    interface AssetFolderPositionPayload {
      /** 0 means move to the root level. */
      parentId: number
      /** Zero-based position among the target parent's children. */
      index: number
    }

    interface AssetUploadPayload {
      file: File
      /** 0 or omitted means ungrouped. */
      folderId?: number | null
    }

    interface AssetMovePayload {
      /** 0 means move to ungrouped. */
      folderId: number
    }

    interface AssetBatchMovePayload extends AssetMovePayload {
      assetIds: number[]
    }

    interface AssetBatchDeletePayload {
      assetIds: number[]
    }

    interface AssetBatchDeleteResult {
      deletedAssetIds: number[]
      skippedReferencedAssetIds: number[]
    }

    interface AssetDisplayNamePayload {
      /** Filename without the immutable extension. */
      displayName: string
    }
  }

  namespace Content {
    type BannerStatus = 'ENABLED' | 'DISABLED'
    type BannerJumpType = 'NONE' | 'PRODUCT' | 'CATEGORY' | 'COUPON' | 'APP_PATH' | 'URL'
    type BannerList = Api.Common.PaginatedResponse<BannerItem>

    interface BannerQueryParams extends Partial<Api.Common.CommonSearchParams> {
      title?: string
      status?: BannerStatus
    }

    interface BannerItem {
      id: number
      title: string
      subtitle: string
      imageFileId: number
      imageUrl: string
      jumpType: BannerJumpType
      jumpTargetId?: number | null
      jumpPath?: string | null
      status: BannerStatus
      sortOrder: number
      startAt?: string | null
      endAt?: string | null
      createdAt: string
      updatedAt: string
    }

    interface BannerForm {
      title: string
      subtitle: string
      imageFileId: number | null
      imageUrl: string
      jumpType: BannerJumpType
      jumpTargetId?: number | null
      jumpPath?: string
      status: BannerStatus
      sortOrder: number
      startAt?: string | null
      endAt?: string | null
    }

    type HomeItemStatus = 'ENABLED' | 'DISABLED'
    type HomeProductSection = 'HOT' | 'RECOMMENDED'

    interface HomeCategoryItem {
      id: number
      categoryId: number
      categoryName: string
      categoryStatus: HomeItemStatus
      imageFileId: number
      imageUrl: string
      sortOrder: number
      status: HomeItemStatus
      createdAt: string
      updatedAt: string
    }

    interface HomeCategoryForm {
      categoryId: number | null
      imageFileId: number | null
      sortOrder: number
      status: HomeItemStatus
    }

    interface HomeAutoFillForm {
      targetCount: number
    }

    interface HomeAutoFillResult {
      targetCount: number
      existingCount: number
      addedCount: number
      finalCount: number
      insufficient: boolean
      addedSpuIds: number[]
    }

    interface HomeCategoryOption {
      id: number
      parentId: number
      name: string
      icon: string
    }

    interface HomeProductItem {
      id: number
      sectionType: HomeProductSection
      spuId: number
      productTitle: string
      productSubtitle: string
      productStatus: string
      categoryName: string
      imageFileId?: number | null
      imageUrl: string
      productImageUrl: string
      displayImageUrl: string
      minPriceCent?: number | null
      maxPriceCent?: number | null
      sortOrder: number
      status: HomeItemStatus
      displayBadgeText: string
      displayBadgeTone: Api.Product.ProductBadgeTone
      createdAt: string
      updatedAt: string
    }

    interface HomeProductForm {
      spuId: number | null
      imageFileId: number | null
      sortOrder: number
      status: HomeItemStatus
    }

    interface HomeProductOption {
      id: number
      categoryId: number
      categoryName: string
      title: string
      subtitle: string
      mainImage: string
      minPriceCent?: number | null
      maxPriceCent?: number | null
    }

    interface HomeProductOptionQuery extends Api.Common.CommonSearchParams {
      keyword?: string
    }

    type HomeProductOptionList = Api.Common.PaginatedResponse<HomeProductOption>

    interface ContactSetting {
      phone: string
      updatedAt: string
    }

    interface ContactForm {
      phone: string
    }
  }

  namespace Customer {
    type CustomerStatus = 'ENABLED' | 'DISABLED'

    type CustomerList = Api.Common.PaginatedResponse<CustomerListItem>

    interface CustomerListItem {
      id: string
      nickname: string
      phoneNumber?: string | null
      phoneAuthorized: boolean
      status: CustomerStatus
      couponTotalCount: number
      couponAvailableCount: number
      couponUsedCount: number
      lastLoginAt?: string | null
      createdAt: string
      updatedAt: string
    }

    type CustomerSearchParams = Partial<
      Api.Common.CommonSearchParams & {
        keyword: string
        status: CustomerStatus
      }
    >

    interface IssuableCouponTemplate {
      id: number
      name: string
      description: string
      couponType: Api.Marketing.CouponType
      discountType: Api.Marketing.DiscountType
      thresholdCent: number
      discountCent: number
      scopeType: Api.Marketing.CouponScopeType
      scopeValue: string
      stockRemaining: number
      perUserLimit: number
      userClaimCount: number
      validStartAt: string
      validEndAt: string
    }

    interface CouponIssueForm {
      templateId: number
      note?: string
    }

    interface DirectCouponIssueForm {
      name: string
      description?: string
      couponType: Api.Marketing.CouponType
      thresholdCent: number
      discountCent: number
      validStartAt: string
      validEndAt: string
      note?: string
    }

    interface CouponIssueResult {
      userCouponId: number
      templateId: number
      templateName: string
      status: 'CLAIMED'
      validStartAt: string
      validEndAt: string
      issuedAt: string
    }
  }

  namespace Marketing {
    type CouponTemplateStatus = 'ENABLED' | 'DISABLED'
    type CouponDistributionMode = 'PUBLIC' | 'DIRECT'
    type CouponIssueSource = 'SELF_CLAIM' | 'ADMIN_ISSUE' | 'ADMIN_DIRECT'
    type UserCouponStatus = 'CLAIMED' | 'LOCKED' | 'USED' | 'EXPIRED'
    type CouponType = 'NO_THRESHOLD' | 'MIN_SPEND'
    type DiscountType = 'AMOUNT_OFF' | 'PERCENT_OFF'
    type CouponScopeType = 'ALL' | 'PRODUCT' | 'CATEGORY'

    type CouponTemplateList = Api.Common.PaginatedResponse<CouponTemplate>

    interface CouponTemplate {
      id: number
      name: string
      description: string
      couponType: CouponType
      discountType: DiscountType
      thresholdCent: number
      discountCent: number
      scopeType: CouponScopeType
      scopeValue: string
      strategyKey: string
      totalStock: number
      claimedCount: number
      stockRemaining: number
      perUserLimit: number
      validStartAt: string
      validEndAt: string
      status: CouponTemplateStatus
      sortOrder: number
      createdAt: string
      updatedAt: string
      distributionMode: CouponDistributionMode
      audienceUserId?: string | null
      audienceNickname?: string | null
      audiencePhoneNumber?: string | null
    }

    interface CouponTemplateForm {
      name: string
      description: string
      couponType: CouponType
      discountType: DiscountType
      thresholdCent: number
      discountCent: number
      scopeType: CouponScopeType
      scopeValue: string
      strategyKey: string
      totalStock: number
      perUserLimit: number
      validStartAt: string
      validEndAt: string
      status: CouponTemplateStatus
      sortOrder: number
    }

    type CouponTemplateSearchParams = Partial<
      Api.Common.CommonSearchParams & {
        name: string
        status: CouponTemplateStatus
        distributionMode: CouponDistributionMode
      }
    >

    type CouponClaimList = Api.Common.PaginatedResponse<CouponClaimRecord>

    interface CouponClaimRecord {
      id: number
      templateId: number
      templateName: string
      distributionMode: CouponDistributionMode
      userId: string
      userNickname: string
      userPhoneNumber?: string | null
      userCouponId: number
      couponType: CouponType
      discountType: DiscountType
      thresholdCent: number
      discountCent: number
      scopeType: CouponScopeType
      scopeValue: string
      status: UserCouponStatus
      validStartAt: string
      validEndAt: string
      usedOrderId?: number | null
      usedAt?: string | null
      issueSource: CouponIssueSource
      operatorAdminUserId?: number | null
      operatorDisplayName?: string | null
      issueNote: string
      claimedAt: string
    }

    type CouponClaimSearchParams = Partial<
      Api.Common.CommonSearchParams & {
        templateName: string
        userKeyword: string
        distributionMode: CouponDistributionMode
        issueSource: CouponIssueSource
        status: UserCouponStatus
      }
    >
  }

  namespace Order {
    type OrderStatus =
      | 'CREATED'
      | 'PAYING'
      | 'PAID'
      | 'SHIPPED'
      | 'COMPLETED'
      | 'CLOSED'
      | 'REFUNDING'
      | 'REFUNDED'
    type AdminOrderStatusGroup =
      | 'ALL'
      | 'UNPAID'
      | 'TO_SHIP'
      | 'TO_RECEIVE'
      | 'COMPLETED'
      | 'CLOSED'
      | 'REFUNDING'
      | 'REFUNDED'
    type UserSearchType = 'USER_ID' | 'USER_NAME' | 'USER_PHONE'
    type OrderSource = 'CART' | 'DIRECT' | 'MINI_PROGRAM'
    type LogisticsType = 1 | 2 | 3 | 4
    type DeliveryMode = 1
    type ShipmentStatus = 'SHIPPED'
    type WechatShippingUploadStatus =
      | 'SKIPPED'
      | 'UPLOADING'
      | 'UPLOADED'
      | 'FAILED'
      | 'UNAVAILABLE'
      | 'UNKNOWN'
    type WechatProviderMode = 'REAL' | 'MOCK' | 'DISABLED' | 'UNKNOWN'
    type WechatShippingCapabilityState = 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN'

    type OrderList = Api.Common.PaginatedResponse<OrderListItem>

    interface ActiveAfterSaleSummary {
      afterSaleId: number
      afterSaleNo: string
      afterSaleType: string
      status: string
      requestedAmountCent: number
      createdAt: string
    }

    interface OrderListItem {
      orderId: number
      orderNo: string
      status: OrderStatus
      userNickname: string
      productAmountCent: number
      couponDiscountCent: number
      freightCent: number
      payableAmountCent: number
      paidAmountCent: number
      receiverName: string | null
      receiverPhone: string | null
      productTitle: string
      productSubtitle: string | null
      mainImage: string | null
      skuImage: string | null
      displayImage: string | null
      specText: string | null
      firstItemQuantity: number
      itemCount: number
      canShip: boolean
      activeAfterSale: ActiveAfterSaleSummary | null
      createdAt: string
    }

    type OrderSearchParams = Partial<
      Api.Common.CommonSearchParams & {
        orderNo: string
        status: OrderStatus
        statusGroup: AdminOrderStatusGroup
        userSearchType: UserSearchType
        userKeyword: string
        receiverName: string
        receiverPhone: string
        createdStart: string
        createdEnd: string
        trackingNo: string
      }
    >

    interface OrderStatusCounts {
      all: number
      unpaid: number
      toShip: number
      toReceive: number
      completed: number
      closed: number
      refunding: number
      refunded: number
    }

    interface OrderStatusLog {
      id: number
      orderId: number
      afterSaleId?: number | null
      fromStatus: OrderStatus | null
      toStatus: OrderStatus
      eventType: string
      operatorType: string
      operatorId: number | null
      description: string | null
      createdAt: string
    }

    interface OrderItem {
      orderItemId: number
      skuId: number
      spuId: number
      productTitle: string
      productSubtitle: string
      mainImage: string
      skuImage: string
      displayImage: string
      skuCode: string
      specText: string
      originalPriceCent: number
      unitPriceCent: number
      retailUnitPriceCent: number
      wholesaleTierMinQuantity: number | null
      quantity: number
      lineOriginalAmountCent: number
      lineAmountCent: number
    }

    interface OrderDetail {
      orderId: number
      orderNo: string
      status: OrderStatus
      source: OrderSource | string
      userId: string
      userNickname: string
      userPhone: string | null
      productOriginalAmountCent: number
      productAmountCent: number
      userCouponId: number | null
      couponName: string | null
      couponDiscountCent: number
      freightCent: number
      payableAmountCent: number
      paidAmountCent: number
      itemCount: number
      refundedAmountCent: number
      receiverName: string | null
      receiverPhone: string | null
      receiverAddress: string | null
      paymentTransactionId: string | null
      merchantTradeNo: string | null
      outTradeNo?: string | null
      transactionId?: string | null
      paymentStatus?: string | null
      paidAt?: string | null
      canShip: boolean
      activeAfterSale: ActiveAfterSaleSummary | null
      shipment?: Shipment | null
      closeReason: string | null
      closedAt: string | null
      createdAt: string
      shippedAt: string | null
      completedAt: string | null
      refundingAt: string | null
      refundedAt: string | null
      items: OrderItem[]
    }

    interface Shipment {
      shipmentId: number
      orderId: number
      logisticsType: LogisticsType
      deliveryMode: DeliveryMode
      itemDesc: string
      expressCompanyCode?: string | null
      expressCompanyName?: string | null
      trackingNo?: string | null
      shipmentNote?: string | null
      localShipmentStatus: ShipmentStatus
      wechatProviderMode: WechatProviderMode
      wechatUploadStatus: WechatShippingUploadStatus
      wechatErrorCode?: string | null
      wechatErrorMessage?: string | null
      retryCount: number
      shippedAt?: string | null
      uploadTime?: string | null
      wechatUploadedAt?: string | null
      lastAttemptAt?: string | null
    }

    interface ShipOrderForm {
      logisticsType: LogisticsType
      itemDesc: string
      expressCompanyCode?: string
      trackingNo?: string
      consignorContact?: string
      shipmentNote?: string
    }

    interface WechatShippingCapability {
      uploadEnabled: boolean
      providerMode: WechatProviderMode
      state: WechatShippingCapabilityState
      tradeManaged?: boolean | null
      errorCode?: string | null
      errorMessage?: string | null
      checkedAt: string
    }

    interface WechatDeliveryCompany {
      deliveryId: string
      deliveryName: string
      syncedAt: string
    }
  }

  namespace Amap {
    interface Config {
      enabled: boolean
      keyConfigured: boolean
      miniProgramKeyMasked: string
      updatedAt?: string | null
    }

    interface ConfigForm {
      enabled: boolean
      miniProgramKey?: string
    }
  }

  namespace ImageCompression {
    type ConfigSource = 'AUTO' | 'ENV' | 'DB'
    type OutputFormat = 'WEBP'

    interface Config {
      requestedEnabled: boolean
      effectiveEnabled: boolean
      configSource: ConfigSource
      persisted: boolean
      defaultConfigSource: ConfigSource
      keyConfigured: boolean
      apiKeyMasked: string
      outputFormat: OutputFormat
      preserveMetadata: false
      monthlyLimit: number | null
      compressionCount: number | null
      remainingCount: number | null
      quotaPeriod: string | null
      lastCheckedAt: string | null
      autoDisabledReason: string | null
      updatedAt: string | null
    }

    interface ConfigForm {
      requestedEnabled: boolean
      configSource: ConfigSource
      apiKey?: string
      monthlyLimit?: number | null
    }
  }

  namespace Payment {
    type ConfigSource = 'AUTO' | 'ENV' | 'DB'
    /** Only PUBLIC_KEY is selectable; legacy DB values can still appear through Config.verifyMode as string. */
    type VerifyMode = 'PUBLIC_KEY'

    type ConfigList = Api.Common.PaginatedResponse<Config>

    interface Config {
      id: number
      source: ConfigSource | string
      configName: string
      appIdMasked: string
      mchIdMasked: string
      merchantSerialNoMasked: string
      apiV3KeyConfigured: boolean
      privateKeyFileId?: number | null
      merchantCertificateFileId: number | null
      verifyMode: VerifyMode | string
      wechatPublicKeyIdMasked?: string | null
      wechatPublicKeyFileId?: number | null
      notifyUrl: string
      refundNotifyUrl: string
      enabled: boolean
      status: string
      createdAt?: string | null
      updatedAt?: string | null
    }

    type EffectiveConfig = Omit<Config, 'id'> & { id: number | null }

    interface EnvironmentConfig {
      available: boolean
      config?: EffectiveConfig | null
    }

    interface ConfigSourceSetting {
      source: ConfigSource
      persisted: boolean
      defaultSource: ConfigSource
    }

    interface ConfigSourceForm {
      source: ConfigSource
    }

    type ConfigSearchParams = Partial<Api.Common.CommonSearchParams>

    interface ConfigForm {
      configName: string
      appId: string
      mchId: string
      merchantSerialNo: string
      apiV3Key?: string
      privateKeyFileId: number | null
      merchantCertificateFileId: number | null
      verifyMode: VerifyMode
      wechatPublicKeyId?: string
      wechatPublicKeyFileId?: number | null
      notifyUrl: string
      refundNotifyUrl: string
    }
  }

  namespace AfterSale {
    type AfterSaleType = 'REFUND_ONLY' | 'RETURN_REFUND'
    type AfterSaleStatus =
      | 'REQUESTED'
      | 'APPROVED'
      | 'REJECTED'
      | 'REFUNDING'
      | 'REFUNDED'
      | 'REFUND_FAILED'
    type RefundOrderStatus = 'PROCESSING' | 'SUCCESS' | 'FAILED'
    type AdminAfterSaleStatusGroup =
      | 'ALL'
      | 'PENDING_REVIEW'
      | 'REFUNDING'
      | 'REFUNDED'
      | 'REJECTED'
      | 'REFUND_FAILED'
    type UserSearchType = 'USER_ID' | 'USER_NAME' | 'USER_PHONE'

    type List = Api.Common.PaginatedResponse<Summary>

    interface SearchParams extends Partial<Api.Common.CommonSearchParams> {
      status?: AfterSaleStatus
      statusGroup?: AdminAfterSaleStatusGroup
      afterSaleId?: number
      afterSaleNo?: string
      orderNo?: string
      userSearchType?: UserSearchType
      userKeyword?: string
      afterSaleType?: AfterSaleType
      createdStart?: string
      createdEnd?: string
      refundNo?: string
    }

    interface StatusCounts {
      all: number
      pendingReview: number
      refunding: number
      refunded: number
      rejected: number
      refundFailed: number
    }

    interface Summary {
      id: number
      afterSaleNo: string
      orderId: number
      orderNo: string
      userId: string
      userNickname: string
      afterSaleType: AfterSaleType | string
      status: AfterSaleStatus | string
      reason: string
      requestedAmountCent: number
      createdAt: string
    }

    interface Item {
      id: number
      afterSaleNo: string
      orderId: number
      orderNo: string
      userId: string
      userNickname: string
      afterSaleType: AfterSaleType | string
      status: AfterSaleStatus | string
      reason: string
      description?: string | null
      requestedAmountCent: number
      approvedAmountCent?: number | null
      auditNote?: string | null
      reviewedBy?: number | null
      reviewedAt?: string | null
      createdAt: string
      evidenceFileIds: number[]
      evidenceFiles?: EvidenceFile[]
      refundOrder?: RefundOrder | null
    }

    interface OrderContext {
      orderId: number
      orderNo: string
      receiverName: string | null
      receiverPhone: string | null
      receiverAddress: string | null
      productAmountCent: number
      paidAmountCent: number
      itemCount: number
      items: Api.Order.OrderItem[]
    }

    interface Detail extends Item {
      orderContext: OrderContext
    }

    type Record = Api.Order.OrderStatusLog

    interface EvidenceFile {
      fileId: number
      originalFilename: string
      contentType: string
      sizeBytes: number
      scope: Api.Storage.AssetScope | string
      mediaKind: Api.Storage.MediaKind | string
      visibility: Api.Storage.Visibility | string
      status: Api.Storage.AssetStatus | string
    }

    interface RefundOrder {
      id: number
      afterSaleId: number
      orderId: number
      paymentOrderId: number
      outRefundNo: string
      refundId?: string | null
      refundAmountCent: number
      status: RefundOrderStatus | string
      callbackStatus?: string | null
      lastErrorCode?: string | null
      lastErrorMessage?: string | null
      requestedAt?: string | null
      successAt?: string | null
    }

    interface AuditPayload {
      approvedAmountCent?: number | null
      auditNote?: string
    }

    interface RefundOperationPayload {
      note: string
    }

    interface RefundOperationResponse {
      action: string
      result: string
      providerStatus: string
      resubmitted: boolean
      afterSale: Item
    }
  }
}
