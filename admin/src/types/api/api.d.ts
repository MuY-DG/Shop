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
  }

  /** 系统管理类型 */
  namespace SystemManage {
    /** 用户列表 */
    type UserList = Api.Common.PaginatedResponse<UserListItem>

    /** 用户列表项 */
    interface UserListItem {
      id: number
      avatar: string
      status: string
      userName: string
      userGender: string
      nickName: string
      userPhone: string
      userEmail: string
      userRoles: string[]
      createBy: string
      createTime: string
      updateBy: string
      updateTime: string
    }

    /** 用户搜索参数 */
    type UserSearchParams = Partial<
      Pick<UserListItem, 'id' | 'userName' | 'userGender' | 'userPhone' | 'userEmail' | 'status'> &
        Api.Common.CommonSearchParams
    >

    /** 角色列表 */
    type RoleList = Api.Common.PaginatedResponse<RoleListItem>

    /** 角色列表项 */
    interface RoleListItem {
      roleId: number
      roleName: string
      roleCode: string
      description: string
      enabled: boolean
      createTime: string
    }

    /** 角色搜索参数 */
    type RoleSearchParams = Partial<
      Pick<RoleListItem, 'roleId' | 'roleName' | 'roleCode' | 'description' | 'enabled'> &
        Api.Common.CommonSearchParams & {
          startTime: string | null
          endTime: string | null
        }
    >
  }

  namespace Product {
    type ProductStatus = 'DRAFT' | 'ON_SALE' | 'OFF_SALE'
    type CategoryStatus = 'ENABLED' | 'DISABLED'
    type SkuStatus = 'ENABLED' | 'DISABLED'

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
      createdAt: string
      updatedAt: string
    }

    type SpuSearchParams = Partial<
      Api.Common.CommonSearchParams & {
        categoryId: number
        title: string
        status: ProductStatus
      }
    >

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
      originalPriceCent: number
      stockAvailable: number
      weightGram: number
      image: string
      imageFileId?: number | null
      status: SkuStatus
      sortOrder: number
    }

    interface SpuDetail {
      id: number
      categoryId: number
      categoryName: string
      title: string
      subtitle: string
      mainImage: string
      mainImageFileId?: number | null
      sellingPoints: string
      detailHtml: string
      sortOrder: number
      status: ProductStatus
      images: ProductImage[]
      skus: Sku[]
      createdAt: string
      updatedAt: string
    }

    interface SpuForm {
      categoryId: number
      title: string
      subtitle: string
      mainImage: string
      mainImageFileId?: number | null
      sellingPoints: string
      detailHtml: string
      sortOrder: number
      images: ProductImageForm[]
      skus: Sku[]
    }

    interface StockAdjustmentForm {
      quantityDelta: number
      reason: string
    }
  }

  namespace Storage {
    type Purpose =
      | 'PRODUCT_IMAGE'
      | 'PRODUCT_SKU_IMAGE'
      | 'CATEGORY_ICON'
      | 'HOME_BANNER'
      | 'MARKETING_IMAGE'
      | 'APP_ICON'
      | 'RICH_TEXT_IMAGE'
      | 'PAYMENT_CERTIFICATE'
      | 'AFTER_SALE_IMAGE'
      | 'REFUND_EVIDENCE'

    type Visibility = 'PUBLIC' | 'PRIVATE'
    type FileStatus = 'ACTIVE' | 'DELETED'
    type UploadedByType = 'ADMIN' | 'APP'
    type CategoryStatus = 'ENABLED' | 'DISABLED'

    type UsageType =
      | 'PRODUCT_CATEGORY_ICON'
      | 'PRODUCT_SPU_MAIN'
      | 'PRODUCT_SPU_GALLERY'
      | 'PRODUCT_SKU_IMAGE'
      | 'PRODUCT_DETAIL_HTML'
      | 'HOME_BANNER'
      | 'ORDER_ITEM_SNAPSHOT'
      | 'AFTER_SALE_EVIDENCE'
      | 'PAYMENT_CONFIGURATION'

    type UsageOwnerType =
      | 'PRODUCT_CATEGORY'
      | 'PRODUCT_SPU'
      | 'PRODUCT_SKU'
      | 'HOME_BANNER'
      | 'ORDER_ITEM'
      | 'AFTER_SALE'
      | 'PAYMENT_CONFIG'

    type FileList = Api.Common.PaginatedResponse<FileItem>

    interface FileQueryParams extends Partial<Api.Common.CommonSearchParams> {
      purpose?: Purpose
      assetCategoryId?: number
      visibility?: Visibility
      status?: FileStatus
    }

    interface FileItem {
      id: number
      purpose: Purpose
      assetCategoryId?: number | null
      visibility: Visibility
      provider: string
      originalFilename: string
      contentType: string
      extension: string
      sizeBytes: number
      sha256?: string
      width?: number | null
      height?: number | null
      status: FileStatus
      uploadedByType: UploadedByType
      uploadedById?: number | null
      url?: string | null
      publicUrl?: string | null
      createdAt: string
      updatedAt: string
      deletedAt?: string | null
      usages?: FileUsage[]
    }

    interface FileUsage {
      id: number
      fileId: number
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

    interface AssetCategory {
      id: number
      parentId: number
      name: string
      code: string
      description?: string | null
      sortOrder: number
      status: CategoryStatus
      createdAt: string
      updatedAt: string
      children: AssetCategory[]
    }

    interface AssetCategoryForm {
      parentId: number
      name: string
      code: string
      description?: string
      sortOrder: number
      status: CategoryStatus
    }

    interface UploadPayload {
      purpose: Purpose
      file: File
      assetCategoryId?: number | null
    }

    interface MovePayload {
      assetCategoryId: number
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
  }

  namespace Marketing {
    type CouponTemplateStatus = 'ENABLED' | 'DISABLED'
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
      }
    >
  }

  namespace Order {
    type OrderStatus = 'CREATED' | 'PAID' | 'CLOSED' | 'REFUNDED'
    type OrderSource = 'MINI_PROGRAM'

    type OrderList = Api.Common.PaginatedResponse<OrderListItem>

    interface OrderListItem {
      orderId: number
      orderNo: string
      status: OrderStatus
      productAmountCent: number
      couponDiscountCent: number
      freightCent: number
      payableAmountCent: number
      paidAmountCent: number
      productTitle: string
      itemCount: number
      createdAt: string
    }

    type OrderSearchParams = Partial<
      Api.Common.CommonSearchParams & {
        orderNo: string
        status: OrderStatus
      }
    >

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
      productOriginalAmountCent: number
      productAmountCent: number
      userCouponId: number | null
      couponName: string | null
      couponDiscountCent: number
      freightCent: number
      payableAmountCent: number
      paidAmountCent: number
      receiverName: string | null
      receiverPhone: string | null
      receiverAddress: string | null
      paymentTransactionId: string | null
      merchantTradeNo: string | null
      closeReason: string | null
      closedAt: string | null
      createdAt: string
      items: OrderItem[]
    }
  }
}
