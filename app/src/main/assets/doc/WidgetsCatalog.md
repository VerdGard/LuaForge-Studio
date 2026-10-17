# LuaForge-Studio 内置控件总目录 & UI 配方

> **这是"别再手写布局"的权威清单。** 需要底部导航、标签页、侧滑抽屉、工具栏、列表、
> 卡片、进度指示等,先在本文件里找现成控件与配方,直接复制改写;**不要**用 `ImageView`
> 画图标 + `LinearLayout` 手动拼一个假的底部栏。

本文件的每一段配方都可在布局助手中直接生成(模板 `app/src/main/assets/templates/*.zip`),
或用 `loadlayout` 加载。所有类名都已注册短名,写短名即可。

---

## 〇、铁律

1. **能用控件就不要手画**:底部导航用 `BottomNavigationView`,标签页用 `TabLayout` + `ViewPager2`,
   侧滑菜单用 `DrawerLayout` + `NavigationView`,顶部栏用 `MaterialToolbar` / `AppBarLayout`,
   浮动按钮用 `FloatingActionButton`,进度用 `LinearProgressIndicator` / `CircularProgressIndicator`,
   卡片用 `MaterialCardView`,文本输入用 `MaterialTextField`。**这些都内置,无需自己拼。**
2. **列表用 `RecyclerView`**(或 `RecyclerAdapterUtil` 一行建适配器),不要用 `ScrollView` 里塞一堆 `LinearLayout` 当列表。
3. **图标优先用系统资源**:`androidx.R.drawable.abc_ic_search_api_material`、`material.R.drawable.material_ic_edit_black_24dp` 等,
   比 PNG 图标省事且自带主题色。`R.drawable.icon` 是项目图标。
4. **导航类控件要挂菜单**:`BottomNavigationView` / `NavigationView` 通过 `.Menu.add(...)` 动态加项,不要塞子 View。
5. **页面切换用 Fragment**:`BottomNavigationView` 切页用 `LuaFragment` + `getSupportFragmentManager()`,不要 `setContentView` 反复重设。

---

## 一、控件短名总表(可直接写在布局表第一项)

### 基础控件(`Basic Controls`)
`AppCompatButton` `AppCompatEditText` `AppCompatTextView` `AppCompatImageView` `CircleImageView`
`TextView` `EditText` `Button` `ImageView` `Space`

### 选择/开关(`Selection/Switch`)
`AppCompatCheckBox` `AppCompatRadioButton` `SwitchMaterial` `MaterialSwitch` `MaterialCheckBox`
`MaterialRadioButton` `CheckBox` `RadioButton` `Switch`

### 列表与分页(`List & Pagination`)
`RecyclerView` `ListView` `ExpandableListView` `ViewPager2` `AppCompatSpinner` `GridView`
`HorizontalListView` `PageView` `PageLayout` `ExListView` `StackView` `Spinner`

### 数据输入与展示(`Data Input & Display`)
`SeekBar` `ProgressBar` `NumberPicker` `AutoCompleteTextView` `AppCompatAutoCompleteTextView`
`AppCompatMultiAutoCompleteTextView` `SearchView` `TextInputLayout` `TextInputEditText`
`TextClock` `Chronometer` `QuickContactBadge` **`MaterialTextField`**(IDE 内置)

### 布局容器(`Layout Containers`)
`LinearLayout` `LinearLayoutCompat` `FrameLayout` `RelativeLayout` `ConstraintLayout`
`CoordinatorLayout` `CardView` `GridLayout` `ScrollView` `HorizontalScrollView` `NestedScrollView`
`TableLayout` `TableRow` `SwipeRefreshLayout` `SlidingPaneLayout` `ViewAnimator` `ViewFlipper` `ViewSwitcher`

### Material 组件(`Material Components`)
`AppBarLayout` `BottomAppBar` `BottomNavigationView` `Chip` `ChipGroup` `FloatingActionButton`
`ExtendedFloatingActionButton` `TabLayout` `NavigationView` `MaterialCardView` `Slider` `RangeSlider`
`MaterialDivider` `CollapsingToolbarLayout` `MaterialToolbar` `MaterialButton` `MaterialTextView`

### 导航与操作(`Navigation & Action`)
`Toolbar` `MaterialToolbar` `SearchBar` `BottomNavigationView` `NavigationView` `TabHost` `TabWidget`
`ZoomButton` `ZoomControls`

### 进度与指示器(`Progress & Indicators`)
`ProgressBar` `LinearProgressIndicator` `CircularProgressIndicator` `SeekBar`

### 特殊视图(`Special Views`)
`VideoView` `ExoVideoView` `SimpleVideoView` `MusicPlayerBar` `ExoAudioPlayer` `WebView`
`AnalogClock` `PullingLayout`

### 日期与时间(`Date & Time`)
`DatePicker` `TimePicker` `CalendarView`

---

## 二、IDE 随附自有控件(编译进 core,非三方库)

| 短名 | 说明 | 文档 |
| --- | --- | --- |
| `MaterialTextField` | Material 文本输入框(含 error/helper/圆角/尾部图标) | `MaterialTextField.md` |
| `ExoVideoView` | ExoPlayer 视频播放器(自带控制器) | `ExoVideoView.md` |
| `SimpleVideoView` | 无控制器视频播放器(自定义控制条) | `SimpleVideoView.md` |
| `MusicPlayerBar` | 一体化音乐播放条(上一首/播放/下一首/进度) | `MusicPlayerBar.md` |
| `ExoAudioPlayer` | 纯音频播放器(背景音乐/音效) | `ExoAudioPlayer.md` |

### 媒体格式支持(4 个播放控件共用)

`ExoVideoView` / `SimpleVideoView` / `ExoAudioPlayer` / `MusicPlayerBar` 共用 `MediaFormats`,
**不需要为不同容器格式换控件或转码**,把地址或本地路径直接丢给 `setMediaUri()` 即可。

| 类别 | 已支持格式 |
| --- | --- |
| 视频容器 | `mp4` `mkv`(Matroska) `webm` `flv` `ts` `ps` `mpg` `avi` `mov` `3gp` `m4v` `wmv` |
| 音频容器 | `mp3` `aac`(ADTS) `m4a` `flac` `ogg`(Vorbis/Opus) `wav` `amr` `ac3` `ac4` `opus` `mka` |
| 自适应流 | `m3u8`(HLS) `mpd`(DASH) `ism`/`Manifest`(SmoothStreaming) |
| 实时流 | `rtsp://` `rtmp://` `rtmps://` `udp://` `rtp://` |
| 其他 | `data:`(内存/base64) `content://` `file://` 与普通本地路径 |

- **无后缀地址也能播**:`inferMimeType()` 会对带 query 的 URL 去掉参数再判断,判断不出时交给
  Media3 的 `DefaultExtractorsFactory` 嗅探文件头,因此 CDN 的 `.../play?id=123` 这类地址同样可用。
- **直播必须显式声明**:`live = true` 才会启用 `LiveConfiguration`(targetOffset 3s / min 1s / max 8s)。
  网络直播/边下边播的 m3u8 不设 `live` 会被当成点播,进度条走完后画面停止刷新。
- **自定义请求头 / UA / 超时**:需要防盗链 `Referer`、鉴权 `Cookie` 或服务端只认特定 UA 时,
  调用 `setHeaders(mapOf("Referer" to "..."))` 再 `setMediaUri()`,不要指望系统默认 UA。
- **支持的协议清单**与所需媒体模块已由 `core` 显式依赖,APK 内已带全,无需 gradle 追加依赖。

> 详细参数见各控件文档;`SKILL.md` 里有"播放器选型"的对照表。

> 还可以用 MCP `list_widgets` 列出全部内置控件及其可写属性。

---

## 三、UI 配方(直接复制改写)

### 配方 1:底部导航栏 BottomNavigationView(别用图片+线性布局手拼!)

`layout.aly`:

```lua
{
  CoordinatorLayout,
  layout_width="fill",
  layout_height="fill",
  fitsSystemWindows = true,
  {
    AppBarLayout,
    layout_width="fill",
    layout_height="wrap",
    {
      MaterialToolbar,
      id="toolbar",
      layout_scrollFlags="exitUntilCollapsed",
      layout_width="fill",
      layout_height="56dp",
    },
  },
  {
    FragmentContainerView,
    id="FragmentContainer",
    layout_width="fill",
    layout_height="fill",
  },
  {
    BottomNavigationView,
    id="bottombar",
    layout_width="fill",
    layout_height="wrap",
    layout_gravity="bottom",
    LabelVisibilityMode=0,
    fitsSystemWindows = true,
  },
}
```

`main.lua`:

```lua
require "import"
import "android.app.*"
import "android.os.*"
import "android.widget.*"
import "android.view.*"
import "androidx.activity.EdgeToEdge"
import "androidx.coordinatorlayout.widget.CoordinatorLayout"
import "androidx.fragment.app.FragmentContainerView"
import "androidx.appcompat.widget.LinearLayoutCompat"
import "com.google.android.material.bottomnavigation.BottomNavigationView"
import "com.google.android.material.textview.MaterialTextView"
import "com.google.android.material.appbar.MaterialToolbar"

EdgeToEdge.enable(activity)
activity.setTheme(R.style.Theme_Material3_Blue_NoActionBar)
  .setContentView(loadlayout("layout"))
  .setSupportActionBar(toolbar)

local function page(title)
  return loadlayout({
    LinearLayoutCompat, orientation="vertical",
    layout_width="fill", layout_height="fill", gravity="center",
    { MaterialTextView, text=title, textSize="28sp", textStyle="bold" },
  })
end

local fm = this.getSupportFragmentManager()
local Fragments = {
  LuaFragment().setLayout(page("搜索")),
  LuaFragment().setLayout(page("编辑")),
  LuaFragment().setLayout(page("语音")),
}
fm.beginTransaction().add(FragmentContainer.getId(), Fragments[1]).commit()

-- 用系统/主题图标,不要自己画
local menu = {
  { title="搜索", icon=androidx.R.drawable.abc_ic_search_api_material },
  { title="编辑", icon=material.R.drawable.material_ic_edit_black_24dp },
  { title="语音", icon=androidx.R.drawable.abc_ic_voice_search_api_material },
}
for k, v in ipairs(menu) do
  bottombar.Menu.add(0, k-1, k-1, v.title).setIcon(v.icon)
end

bottombar.setOnNavigationItemSelectedListener(BottomNavigationView.OnNavigationItemSelectedListener{
  onNavigationItemSelected = function(item)
    fm.beginTransaction()
      .replace(FragmentContainer.getId(), Fragments[item.getItemId()+1])
      .commit()
    return true
  end
})
```

### 配方 2:标签页 TabLayout + ViewPager2

`layout.aly`:

```lua
{
  CoordinatorLayout,
  layout_width="fill", layout_height="fill", fitsSystemWindows=true,
  {
    AppBarLayout, layout_width="fill", layout_height="wrap",
    { MaterialToolbar, id="toolbar", layout_width="fill", layout_height="56dp" },
    { TabLayout, id="mTab", layout_width="fill", layout_height="wrap" },
  },
  {
    ViewPager2,
    id="cvpg",
    layout_behavior="appbar_scrolling_view_behavior",
    layout_width="fill", layout_height="fill",
    pages={ "page1", "page2", "page3" },
  },
}
```

`main.lua`:

```lua
require "import"
import "com.google.android.material.tabs.TabLayoutMediator"
import "androidx.viewpager2.widget.ViewPager2"

local titles = { "首页", "发现", "我的" }
local mediator = TabLayoutMediator(mTab, cvpg, TabLayoutMediator.TabConfigurationStrategy{
  onConfigureTab = function(tab, position)
    tab.setText(titles[position + 1])
  end,
})
mediator.attach()
```

> `ViewPager2` **没有** `setupWithViewPager`,必须用 `TabLayoutMediator`。`pages={...}` 会自动加载同目录同名 `.aly` 分页。

### 配方 3:侧滑抽屉 DrawerLayout + NavigationView

`layout.aly`:

```lua
{
  DrawerLayout,
  id="drawer_layout",
  layout_width="fill", layout_height="fill",
  {
    CoordinatorLayout, layout_width="fill", layout_height="fill", fitsSystemWindows=true,
    { AppBarLayout, layout_width="fill", layout_height="wrap",
      { MaterialToolbar, id="toolbar", layout_width="fill", layout_height="56dp" } },
    { ViewPager2, id="cvpg", layout_behavior="appbar_scrolling_view_behavior",
      layout_width="fill", layout_height="fill", pages={ "page1","page2","page3" } },
  },
  { NavigationView, id="nav_view", layout_width="wrap", layout_height="fill", layout_gravity="left" },
}
```

`main.lua`:

```lua
require "import"
import "androidx.appcompat.app.ActionBarDrawerToggle"
import "androidx.drawerlayout.widget.DrawerLayout"
import "androidx.viewpager2.widget.ViewPager2"
import "com.google.android.material.navigation.NavigationView"

activity.setSupportActionBar(toolbar).getSupportActionBar().setDisplayHomeAsUpEnabled(true)

local toggle = ActionBarDrawerToggle(this, drawer_layout, R.string.open_drawer, R.string.close_drawer)
drawer_layout.setDrawerListener(toggle)
toggle.syncState()

function onOptionsItemSelected(item)
  if item.ItemId == android.R.id.home then
    if not drawer_layout.isDrawerOpen(3) then drawer_layout.openDrawer(3)
    else drawer_layout.closeDrawer(3) end
  end
end

nav_view.addHeaderView(loadlayout("nav_header_main"))

local menu = {
  { title="搜索", icon=androidx.R.drawable.abc_ic_search_api_material },
  { title="编辑", icon=material.R.drawable.material_ic_edit_black_24dp },
  { title="语音", icon=androidx.R.drawable.abc_ic_voice_search_api_material },
}
for k, v in ipairs(menu) do
  nav_view.Menu.add(0, k-1, k-1, v.title).setIcon(v.icon).setCheckable(true)
end
nav_view.setCheckedItem(0)
nav_view.setNavigationItemSelectedListener(NavigationView.OnNavigationItemSelectedListener{
  onNavigationItemSelected = function(item)
    nav_view.invalidate()
    cvpg.setCurrentItem(item.getItemId())
    return true
  end,
})
```

### 配方 4:工具栏 + 折叠 AppBarLayout + CollapsingToolbar

```lua
{
  CoordinatorLayout, layout_width="fill", layout_height="fill", fitsSystemWindows=true,
  {
    AppBarLayout, layout_width="fill", layout_height="wrap",
    {
      CollapsingToolbarLayout,
      layout_width="fill", layout_height="220dp",
      layout_scrollFlags="scroll|exitUntilCollapsed",
      title="标题",
      contentScrim="?attr/colorPrimary",
      {
        MaterialToolbar, id="toolbar", layout_width="fill", layout_height="56dp",
        layout_collapseMode="pin",
      },
    },
  },
  { NestedScrollView, layout_behavior="appbar_scrolling_view_behavior",
    layout_width="fill", layout_height="fill" },
}
```

### 配方 5:Material 卡片 + 列表项(不要用 FrameLayout 拼卡片)

```lua
{
  MaterialCardView,
  id="card",
  layout_width="fill", layout_height="wrap",
  layout_margin="8dp",
  cardCornerRadius="12dp",
  cardElevation="4dp",
  {
    LinearLayoutCompat, orientation="vertical",
    layout_width="fill", layout_height="wrap", padding="16dp",
    { MaterialTextView, text="标题", textSize="18sp", textStyle="bold" },
    { MaterialTextView, text="副标题说明文字", textSize="14sp", textColor="#888888" },
  },
}
```

### 配方 6:进度指示器

```lua
-- 线性(可确定/不确定)
{ LinearProgressIndicator, id="bar", layout_width="fill", layout_height="wrap" }
-- bar.setProgress(60)  -- 0-100
-- 圆形(加载中)
{ CircularProgressIndicator, id="spin", layout_width="48dp", layout_height="48dp" }
```

### 配方 7:浮动操作按钮 FAB

```lua
{
  FloatingActionButton,
  id="fab",
  layout_width="wrap", layout_height="wrap",
  layout_gravity="bottom|end", layout_margin="16dp",
  src="images/ic_email_outline.png",
}
```

```lua
-- 用 Snackbar 反馈,别用 Toast
import "com.google.android.material.snackbar.Snackbar"
fab.onClick = function(view)
  Snackbar.make(view, "已执行操作", Snackbar.LENGTH_LONG).setAction("撤销", nil).show()
end
```

### 配方 8:文本输入(优先 MaterialTextField,自带 error/helper)

```lua
{ MaterialTextField, id="field", layout_width="fill", layout_height="wrap",
  hint="用户名", singleLine=true, boxCornerRadii=16 }
```

```lua
field.setError("不能为空")
field.text = "初始值"
local s = field.getText()
```

### 配方 9:下拉刷新 + 列表

```lua
{
  SwipeRefreshLayout, id="refresh", layout_width="fill", layout_height="fill",
  { RecyclerView, id="list", layout_width="fill", layout_height="fill" },
}
```

参见 `RecyclerAdapterUtil.md`,一行建适配器:
```lua
import "com.luaforge.studio.utils.RecyclerAdapterUtil"  -- 需在 settings.json global_utils 含 RecyclerAdapterUtil
list.setAdapter(createAdapter(data, itemLayout, { onBindViewHolder = function(vh, item) ... end }))
```

### 配方 10:芯片组 ChipGroup(标签选择)

```lua
{
  ChipGroup, id="chips", layout_width="fill", layout_height="wrap",
  singleSelection=true,
  {
    Chip, text="全部", checkable=true, checked=true,
  },
  { Chip, text="图片", checkable=true },
  { Chip, text="视频", checkable=true },
}
```

---

## 四、图标资源速查

| 用途 | 资源 |
| --- | --- |
| 搜索 | `androidx.R.drawable.abc_ic_search_api_material` |
| 语音搜索 | `androidx.R.drawable.abc_ic_voice_search_api_material` |
| 编辑 | `material.R.drawable.material_ic_edit_black_24dp` |
| 返回/菜单 | `android.R.id.home`、`material.R.drawable.*` |
| 项目图标 | `R.drawable.icon` |

> 主题/系统资源优先,`androidx.R`、`material.R`、`android.R` 都已在全局注入(见 SKILL.md §1.6)。

---

## 五、易错提醒

1. **导航控件加菜单用 `.Menu.add(...)`,不是塞子 View**。`BottomNavigationView` / `NavigationView` 的子元素不会显示。
2. **`ViewPager2` 用 `TabLayoutMediator`**,没有 `setupWithViewPager`。
3. **Fragment 切页**,不要反复 `setContentView`。
4. **属性名 = 去掉 `set` 前缀首字母小写**(如 `setLabelVisibilityMode` → `LabelVisibilityMode`、`setContentScrim` → `contentScrim`)。
5. 布局类控件(`BottomNavigationView` 等)不在 `CAN_HAVE_CHILDREN` 里,塞子控件无效。
6. **尺寸**:数字 = px;`"16dp"` / `"20sp"` 才带单位;简写只认 `fill`/`match`/`wrap`。
