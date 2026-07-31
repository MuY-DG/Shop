<!-- 文章发布页面 -->
<template>
  <div>
    <div>
      <div class="max-w-250 mx-auto my-5">
        <!-- 文章标题、类型 -->
        <ElRow :gutter="10">
          <ElCol :span="18">
            <ElInput
              v-model.trim="articleName"
              placeholder="请输入文章标题（最多100个字符）"
              maxlength="100"
            />
          </ElCol>
          <ElCol :span="6">
            <ElSelect v-model="articleType" placeholder="请选择文章类型" filterable>
              <ElOption
                v-for="item in articleTypes"
                :key="item.id"
                :label="item.name"
                :value="item.id"
              />
            </ElSelect>
          </ElCol>
        </ElRow>

        <!-- 富文本编辑器 -->
        <ArtWangEditor class="mt-2.5" v-model="editorHtml" />

        <div class="p-5 mt-5 art-card-xs">
          <h2 class="mb-5 text-xl font-medium">发布设置</h2>
          <ElForm>
            <ElFormItem label="可见">
              <ElSwitch v-model="visible" />
            </ElFormItem>
          </ElForm>

          <div class="flex justify-end">
            <ElButton type="primary" @click="submit" class="w-25">
              {{ pageMode === PageModeEnum.Edit ? '保存' : '发布' }}
            </ElButton>
          </div>
        </div>
      </div>
    </div>

    <!-- <div class="box-border w-70 p-5 border border-[#e3e3e3] rounded-lg">
        <div v-for="(item, index) in outlineList" :key="index">
          <p :class="['h-7.5 text-xs leading-7.5 c-p', item.level === 3 && 'pl-2.5']">{{ item.text }}</p>
        </div>
      </div> -->
  </div>
</template>

<script setup lang="ts">
  import { ApiStatus } from '@/utils/http/status'
  import { PageModeEnum } from '@/enums/formEnum'
  import axios from 'axios'
  import { useCommon } from '@/hooks/core/useCommon'

  defineOptions({ name: 'ArticlePublish' })

  interface ArticleType {
    id: number
    name: string
  }

  interface ArticleDetailResponse {
    code: number
    data: {
      title: string
      blog_class: string
      html_content: string
    }
  }

  const EMPTY_EDITOR_CONTENT = '<p><br></p>'

  const route = useRoute()

  const pageMode = ref<PageModeEnum>(PageModeEnum.Add)
  const articleName = ref('')
  const articleType = ref<number>()
  const articleTypes = ref<ArticleType[]>([])
  const editorHtml = ref('')
  const createDate = ref('')
  const visible = ref(true)

  /**
   * 初始化页面模式（新增或编辑）
   */
  const initPageMode = () => {
    const { id } = route.query
    pageMode.value = id ? PageModeEnum.Edit : PageModeEnum.Add

    if (pageMode.value === PageModeEnum.Edit) {
      getArticleDetail()
    } else {
      createDate.value = formatDate(useNow().value)
    }
  }

  /**
   * 获取文章分类列表
   */
  const getArticleTypes = async () => {
    try {
      const { data } = await axios.get('https://www.qiniu.lingchen.kim/classify.json')
      if (data.code === 200) {
        articleTypes.value = data.data
      }
    } catch (error) {
      console.error('获取文章分类失败:', error)
      ElMessage.error('获取文章分类失败')
    }

    // TODO: 替换为真实 API 调用
    // const res = await ArticleService.getArticleTypes({})
    // if (res.code === ApiStatus.success) {
    //   articleTypes.value = res.data
    // }
  }

  /**
   * 获取文章详情（编辑模式）
   */
  const getArticleDetail = async () => {
    try {
      const { data } = await axios.get<ArticleDetailResponse>(
        'https://www.qiniu.lingchen.kim/blog_list.json'
      )

      if (data.code === ApiStatus.success) {
        const { title, blog_class, html_content } = data.data
        articleName.value = title
        articleType.value = Number(blog_class)
        editorHtml.value = html_content
      }
    } catch (error) {
      console.error('获取文章详情失败:', error)
      ElMessage.error('获取文章详情失败')
    }
  }

  /**
   * 格式化日期为 YYYY-MM-DD 格式
   */
  const formatDate = (date: string | Date): string => {
    return useDateFormat(date, 'YYYY-MM-DD').value
  }

  /**
   * 验证文章表单数据
   */
  const validateArticle = (): boolean => {
    if (!articleName.value.trim()) {
      ElMessage.error('请输入文章标题')
      return false
    }

    if (!articleType.value) {
      ElMessage.error('请选择文章类型')
      return false
    }

    if (!editorHtml.value || editorHtml.value === EMPTY_EDITOR_CONTENT) {
      ElMessage.error('请输入文章内容')
      return false
    }

    return true
  }

  /**
   * 清理代码块中的多余空格
   */
  const cleanCodeContent = (content: string): string => {
    return content.replace(/(\s*)<\/code>/g, '</code>')
  }

  /**
   * 新增文章
   */
  const addArticle = async () => {
    if (!validateArticle()) return

    try {
      const cleanedContent = cleanCodeContent(editorHtml.value)

      // TODO: 替换为真实 API 调用
      // const params = {
      //   title: articleName.value,
      //   type: articleType.value,
      //   content: cleanedContent,
      //   visible: visible.value
      // }
      // const res = await ArticleService.addArticle(params)
      // if (res.code === ApiStatus.success) {
      //   ElMessage.success('文章发布成功')
      //   router.push({ name: 'ArticleList' })
      // }

      console.log('新增文章:', { cleanedContent })
    } catch (error) {
      console.error('发布文章失败:', error)
      ElMessage.error('发布文章失败')
    }
  }

  /**
   * 编辑文章
   */
  const editArticle = async () => {
    if (!validateArticle()) return

    try {
      const cleanedContent = cleanCodeContent(editorHtml.value)

      // TODO: 替换为真实 API 调用
      // const params = {
      //   id: route.query.id,
      //   title: articleName.value,
      //   type: articleType.value,
      //   content: cleanedContent,
      //   visible: visible.value
      // }
      // const res = await ArticleService.editArticle(params)
      // if (res.code === ApiStatus.success) {
      //   ElMessage.success('文章保存成功')
      //   router.push({ name: 'ArticleList' })
      // }

      console.log('编辑文章:', { cleanedContent })
    } catch (error) {
      console.error('保存文章失败:', error)
      ElMessage.error('保存文章失败')
    }
  }

  /**
   * 提交表单（新增或编辑）
   */
  const submit = () => {
    if (pageMode.value === PageModeEnum.Edit) {
      editArticle()
    } else {
      addArticle()
    }
  }

  const { scrollToTop } = useCommon()

  onMounted(() => {
    scrollToTop()
    getArticleTypes()
    initPageMode()
  })
</script>
