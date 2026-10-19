# 可视化屏幕编排器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在管理台提供可变尺寸屏幕编排器，并为 ESP-VoCat 提供可发布的场景模型、触摸动作和模式切换基础能力。

**Architecture:** 先在共享类型层定义场景 schema、设备能力和动作白名单，再在 companion-console 增加本地可编辑的编排器页面和发布 API 适配。服务端增加按设备隔离的场景草稿与发布接口；固件增加场景版本与模式切换契约，保留现有 EmoteDisplay 作为表情渲染路径。资源包通过现有 OTA/MQTT 边界分发，任何失败都回退到内置场景。

**Tech Stack:** React 19、Ant Design 5、TypeScript、Vitest、Spring Boot/Java、ESP-IDF C++、JSON schema、MQTT。

---

### Task 1: 定义场景契约与验证器

**Files:**
- Create: `server/main/companion-console/src/screen-composer/sceneTypes.ts`
- Create: `server/main/companion-console/src/screen-composer/sceneValidation.ts`
- Test: `server/main/companion-console/src/screen-composer/sceneValidation.test.ts`

- [x] **Step 1: 写场景模型测试**

覆盖五种 mode、360×360 圆形画布、图层可见条件、矩形和圆形触摸区、快捷设备动作、受限 MQTT 模板，以及拒绝脚本字段和超出画布坐标。

- [x] **Step 2: 运行测试确认失败**

```bash
cd server/main/companion-console
npm test -- --run src/screen-composer/sceneValidation.test.ts
```

- [x] **Step 3: 实现场景类型和纯函数验证器**

验证器返回结构化 issues，不修改输入；动作只允许 `device_tool` 和 `mqtt_template`，模板变量只允许 deviceId、mode、state、x、y。

- [x] **Step 4: 运行验证器测试**

预期所有 schema 和安全边界测试通过。

### Task 2: 建立屏幕编排器页面

**Files:**
- Create: `server/main/companion-console/src/pages/screenComposer/ScreenComposerPage.tsx`
- Create: `server/main/companion-console/src/pages/screenComposer/screenComposer.css`
- Create: `server/main/companion-console/src/pages/screenComposer/ScreenComposerPage.test.tsx`
- Modify: `server/main/companion-console/src/app/router.tsx`
- Modify: `server/main/companion-console/src/app/navigation.tsx`

- [x] **Step 1: 写页面行为测试**

验证设备尺寸编辑、五种模式选择、图层增删排序、状态条件、触摸热区选择、长按模式说明和 schema 验证错误显示。

- [x] **Step 2: 实现页面骨架**

使用现有 AdminPage、Ant Design Form、List、Select、Upload 和 Drawer。左侧显示按宽高缩放的圆形画布，中间显示图层树，右侧显示属性和动作编辑器。

- [x] **Step 3: 实现模式和画布编辑**

支持 width、height、shape、safeInset 和 mode；预览使用 CSS aspect-ratio 和 border-radius 模拟圆形屏幕。

- [x] **Step 4: 实现图层、状态、触摸区编辑**

支持 image、text、animation 三类图层；状态选项覆盖 idle、listening、thinking、speaking、error；触摸动作支持快捷设备工具选择和 MQTT 模板预览。

- [x] **Step 5: 接入路由并运行页面测试**

```bash
npm test -- --run src/pages/screenComposer/ScreenComposerPage.test.tsx
```

### Task 3: 场景草稿与发布 API

**Files:**
- Create: `server/main/companion-console/src/api/screenScenes.ts`
- Create: `server/main/manager-api/src/main/java/zixuan/modules/screen/`
- Create: `server/main/manager-api/src/main/resources/db/changelog/20261019-screen-scenes.sql`
- Test: `server/main/manager-api/src/test/java/zixuan/modules/screen/`

- [ ] **Step 1: 写 API 契约测试**

覆盖按设备查询草稿、保存草稿、发布新版本、读取活动版本、拒绝覆盖已发布版本和设备能力不匹配。

- [x] **Step 2: 建立数据库表**

保存 device_id、scene_id、mode、schema_version、draft_json、published_version、resource_manifest、created_by 和 timestamps；发布版本单独追加，不原地覆盖。

- [x] **Step 3: 实现管理端 controller/service/dao**

所有查询按当前管理员和设备绑定隔离；发布前执行 schema 和动作白名单验证。

- [x] **Step 4: 接入前端 API**

页面保存草稿和发布时调用后端，发布成功刷新版本历史和当前活动状态。

### Task 4: 设备能力和远程资源契约

**Files:**
- Modify: `firmware/main/boards/esp-vocat/esp_vocat.cc`
- Modify: `firmware/main/mcp_server.cc`
- Create: `firmware/main/screen_scene.h`
- Create: `firmware/main/screen_scene.cc`
- Test: `firmware/tests/test_screen_scene.py`

- [ ] **Step 1: 添加设备能力报告**

上报 360×360、circle、CST816S touch range、五种 mode、image/text/eaf resource types 和资源包上限。

- [ ] **Step 2: 添加场景 schema 解析器**

解析安全白名单字段，拒绝未知动作、脚本内容、越界图层和无效资源引用；解析失败保留内置场景。

- [x] **Step 3: 添加长按模式面板**

长按 800ms 显示模式面板，点击模式更新设置并刷新当前场景；短按继续交给场景热区。

- [x] **Step 4: 连接 EmoteDisplay 和静态图层**

emotion_chat 模式继续走现有 EAF；静态图片和文字通过统一 scene layer 接口渲染，资源分区不可用时继续走默认表情。

- [x] **Step 5: 运行固件测试和编译**

```bash
cd firmware
python3 -m pytest tests/test_screen_scene.py -q
idf.py build
```

### Task 5: 端到端发布和真实设备验证

**Files:**
- Modify: `docs/dynamic-device-wake-word.md`
- Create: `docs/screen-composer.md`
- Create: `server/main/companion-console/e2e/screen-composer.spec.ts`

- [ ] **Step 1: 添加发布和回退文档**

记录 360×360 素材建议、圆形安全区、五种模式、触摸长按、资源校验和旧版本回退。

- [ ] **Step 2: 添加浏览器端发布测试**

验证从设备能力读取到场景发布、模式切换和 MQTT payload 预览。

- [x] **Step 3: 运行前端、后端和固件检查**

运行 console tests/build、manager-api 定向测试、firmware scene tests/build，并检查 git diff。

- [ ] **Step 4: 在真实 ESP-VoCat 上验证**

验证静态图、表情对话、纯文字、长按模式切换、触摸快捷动作、错误资源回退和普通对话保持可用。

