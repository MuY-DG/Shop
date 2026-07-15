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
      userId: string
      userName: string
      email: string
      avatar?: string
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
      roleIds: number[]
    }

    interface UserUpdateForm {
      displayName: string
      email: string
      password?: string
      avatar: string
      status: AdminUserStatus
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
    type ProductTag = 'PROMOTION' | 'HOT_SALE' | 'HOT_RANK' | 'PREMIUM' | 'NEW_ARRIVAL'
    type FreightChargeMode = 'FREE' | 'FIXED'
    type FreightTemplateStatus = 'ENABLED' | 'DISABLED'

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
      weightGram?: number | null
      volumeCubicMeter?: number | null
      image: string
      imageFileId?: number | null
      status: SkuStatus
      defaultSelected: boolean
      combinationKey: string
      specValueKeys: string[]
      sortOrder: number
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
      sortOrder: number
      status: ProductStatus
      images: ProductImage[]
      skus: Sku[]
      specGroups: SpecGroup[]
      tags: ProductTag[]
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
      sortOrder: number
      images: ProductImageForm[]
      skus: Sku[]
      specGroups: SpecGroupForm[]
      tags: ProductTag[]
      guaranteeServiceIds: number[]
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
      localRoot: string
      cosRegion: string
      cosBucket: string
      cosSecretIdMasked: string
      cosSecretKeyConfigured: boolean
    }

    interface ConfigForm {
      provider: Provider
      publicBaseUrl: string
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
      | 'ORDER_ITEM'
      | 'AFTER_SALE'
      | 'PAYMENT_CONFIG'

    type AssetList = Api.Common.PaginatedResponse<Asset>

    interface AssetQueryParams extends Partial<Api.Common.CommonSearchParams> {
      keyword?: string
      mediaKind?: Exclude<MediaKind, 'DOCUMENT'>
      /** 0 means ungrouped; omitted means all folders. */
      folderId?: number
      referenceStatus?: ReferenceStatus
      createdFrom?: string
      createdTo?: string
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
      status: string
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
      userCouponId: string
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
      audienceUserId?: number | null
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
      userId: number
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
      quantity: number
      lineOriginalAmountCent: number
      lineAmountCent: number
    }

    interface OrderDetail {
      orderId: number
      orderNo: string
      status: OrderStatus
      source: OrderSource | string
      userId: number
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
      reviewedBy?: string | null
      reviewedAt?: string | null
      createdAt: string
      evidenceFileIds: number[]
      evidenceFiles?: EvidenceFile[]
      refundOrder?: RefundOrder | null
    }

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
      auditNote: string
    }
  }
}
