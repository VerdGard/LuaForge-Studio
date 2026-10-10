local bindClass = luajava.bindClass
local layout_main

luaproject = luajava.luaextdir

require "classes"
require "layoutData"
method = require "method"

local intent = activity.getIntent()
local layoutContent = intent.getStringExtra("layout_content")
local luapath = intent.getStringExtra("luapath")
-- 三方控件支持:Kotlin 侧设置经 extra 传入,未传则保持默认放行
_G.THIRD_PARTY_WIDGET_SUPPORT = intent.getBooleanExtra("third_party_widget_support", true)

luadir = luapath:gsub("/[^/]+$", "")

local ArrayExpandableListAdapter = bindClass "android.widget.ArrayExpandableListAdapter"
local MaterialAlertDialogBuilder = bindClass "com.google.android.material.dialog.MaterialAlertDialogBuilder"
local View = bindClass "android.view.View"
local File = bindClass "java.io.File"
local MyBottomSheetDialog = require "MyBottomSheetDialog"
local loadlayout = require "loadlayout"
local loadlayout2 = require "loadlayout2"

-- 统一日志出口:由本函数补 [Layouthelper] 标签,调用方只传正文,
-- 避免调用方再拼标签导致 "[Layouthelper] [Layouthelper]" 双前缀
local function logLine(level, msg)
  local logPath = "/storage/emulated/0/LuaForge-Studio/luaforge.log"
  local file = io.open(logPath, "a+")
  if file then
    file:write(string.format("[%s] [%s] [Layouthelper] %s\n", os.date("%Y-%m-%d %H:%M:%S"), level, tostring(msg)))
    file:close()
  end
end

function Error(msg)
  logLine("ERROR", msg)
end

function Info(msg)
  logLine("INFO", msg)
end

-- 三方控件支持:预载项目 libs 目录下的 dex/jar,并打通宿主 classpath 之外的装载器链。
-- 背景:luajava.bindClass 走 Class.forName(只查宿主 APK classpath),看不到 activity.loadDex
-- 追加的 DexClassLoader;aly 中的裸名与 FQN 字符串都要经装载器链解析。
if _G.THIRD_PARTY_WIDGET_SUPPORT then
  local libFiles = {}
  local libsDir = File(luadir .. "/libs")
  local libsExists = libsDir.exists()
  if libsExists then
    local ls = libsDir.listFiles()
    for n = 0, (ls and #ls or 0) - 1 do
      local f = ls[n]
      local name = f.getName()
      if name:find("%.dex$") or name:find("%.jar$") then
        local ok, err = pcall(function() activity.loadDex(f.getAbsolutePath()) end)
        if not ok then
          print("加载三方 dex 失败: " .. name .. " " .. tostring(err))
          Error("加载三方 dex 失败: " .. name .. " " .. tostring(err))
        end
        libFiles[#libFiles + 1] = { name = name, path = f.getAbsolutePath() }
      end
    end
  end

  local loaders = luajava.astable(activity.getClassLoaders())

  local function loadFromLoaders(className)
    for i = 1, #loaders do
      local ok, c = pcall(function() return loaders[i].loadClass(className) end)
      if ok and c then return c end
    end
    return nil
  end

  -- 统一类解析入口:宿主 classpath → dex 装载器链(loadlayout2 解析 FQN 字符串走此入口)
  _G.__luaforgeResolveClass = function(className)
    local ok, c = pcall(bindClass, className)
    if ok and c then return c end
    return loadFromLoaders(className)
  end

  -- 裸类名支持:枚举 libs 内 dex 的类表建立 简单名→全限定名 映射;
  -- 只解析 aly 文本中实际出现的标识符,避免无谓的类加载开销。
  -- Android 14+ 原文件为可写时 DexFile 拒绝打开,优先用 loadDex 第 0 步落在 private_libs 的只读副本。
  local simpleToFull = {}
  local dexEntryCount = 0
  local okDexFile, DexFile = pcall(bindClass, "dalvik.system.DexFile")
  if okDexFile then
    for i = 1, #libFiles do
      local f = libFiles[i]
      local candidates = {
        activity.getFilesDir().getAbsolutePath() .. "/private_libs/" .. f.name,
        f.path
      }
      for k = 1, #candidates do
        local ok, count = pcall(function()
          local df = DexFile(candidates[k])
          local e = df.entries()
          local n = 0
          while e.hasMoreElements() do
            local full = tostring(e.nextElement())
            if not full:find("%$") then
              local short = full:match("[%w_]+$")
              if short and not simpleToFull[short] then
                simpleToFull[short] = full
              end
            end
            n = n + 1
          end
          df.close()
          return n
        end)
        if ok and count > 0 then
          dexEntryCount = dexEntryCount + count
          break
        end
      end
    end
  end

  -- aly 中的裸标识符 → 从 dex 类表匹配简单名,经装载器链解析为类(仅 View 子类,不覆盖已有全局)
  local registered = {}
  if layoutContent then
    local seen = {}
    for ident in layoutContent:gmatch("[%a_][%w_]*") do
      if not seen[ident] then
        seen[ident] = true
        if _G[ident] == nil and simpleToFull[ident] then
          local c = loadFromLoaders(simpleToFull[ident])
          local okView, isView = pcall(function() return View.isAssignableFrom(c) end)
          if c and okView and isView then
            _G[ident] = c
            registered[#registered + 1] = ident .. "->" .. simpleToFull[ident]
          end
        end
      end
    end
  end

  -- 三方控件注册:从 dex 类表挑出 View 子类,注册为全局并加入「添加控件」列表,
  -- 同时暴露解析器供对话框懒解析未预先注册的三方类
  _G.__lfThirdParty = { resolve = loadFromLoaders, map = simpleToFull }
  local tpNames = {}
  do
    local cap = 80
    for short, full in pairs(simpleToFull) do
      if #tpNames >= cap then break end
      if _G[short] == nil then
        local okC, c = pcall(loadFromLoaders, full)
        if okC and c then
          local okV, isV = pcall(function() return View.isAssignableFrom(c) end)
          if okV and isV then
            _G[short] = c
            tpNames[#tpNames + 1] = short
          end
        end
      end
    end
    table.sort(tpNames)
  end
  if #tpNames > 0 and ns and wds then
    ns[#ns + 1] = "Third-party"
    wds[#wds + 1] = tpNames
    if ns2 then ns2[#ns2 + 1] = "三方控件" end
    if wds2 then wds2[#wds2 + 1] = {} end
  end

  -- 诊断埋点:装载器数、dex 类表规模、注册结果。
  -- 项目无三方库时 loaders=0/scanned=0 属正常,记录为 INFO 而非 ERROR。
  do
    local parts = {}
    parts[#parts + 1] = "dex_loaders=" .. tostring(#loaders)
    parts[#parts + 1] = "third_party=true"
    parts[#parts + 1] = "libs=" .. luadir .. "/libs"
    parts[#parts + 1] = "libs_exists=" .. tostring(libsExists)
    parts[#parts + 1] = "lib_files=" .. tostring(#libFiles)
    parts[#parts + 1] = "scanned=" .. tostring(dexEntryCount)
    parts[#parts + 1] = "registered=" .. tostring(#registered)
    parts[#parts + 1] = "third_party_views=" .. tostring(#tpNames)
    Info(table.concat(parts, " "))
    Info("registered: " ..
      ((registered and #registered > 0) and table.concat(registered, ",") or "(none)"))
  end
end

function onError(title, message)
  MaterialAlertDialogBuilder(this)
  .setTitle(tostring(title))
  .setMessage(tostring(message))
  .setPositiveButton("确定", nil)
  .show()
end

try
  result = assert(loadstring("return " .. layoutContent))()
  layout_main = result
 catch(e)
  print("布局字符串解析失败: " .. tostring(e))
  Error(e)
  activity.finish()
end

activity
.setContentView(loadlayout("layouthelper"))
.setSupportActionBar(toolbar)
.getSupportActionBar()
.setDisplayHomeAsUpEnabled(true)

activity.decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)

try
  root.addView(loadlayout2(layout_main, {}))
 catch(e)
  print("不支持编辑此布局." .. e)
  Error(e)
  activity.finish()
end

--属性列表对话框
fd_dlg = MyBottomSheetDialog(activity).setView("dialog_item")
fd_list, fd_title = mDialogListView, mDialogTitle

--属性选择列表
checks = {}
checks.layout_width = { "match_parent", "wrap_content", "Fixed size..." }
checks.layout_height = { "match_parent", "wrap_content", "Fixed size..." }
checks.ellipsize = { "start", "end", "middle", "marquee" }
checks.singleLine = { "true", "false" }
checks.fitsSystemWindows = { "true", "false" }
checks.orientation = { "vertical", "horizontal" }
checks.gravity = { "left", "top", "right", "bottom", "start", "center", "end", "bottom|end", "end|center", "left|center", "top|center", "bottom|center" }
checks.layout_gravity = { "left", "top", "right", "bottom", "start", "center", "end", "bottom|end", "end|center", "left|center", "top|center", "bottom|center" }
checks.scaleType = {
  "matrix",
  "fitXY",
  "fitStart",
  "fitCenter",
  "fitEnd",
  "center",
  "centerCrop",
  "centerInside"
}

function addDir(out, dir, f)
  local ls = f.listFiles()
  if not ls then return end
  for n = 0, #ls - 1 do
    local name = ls[n].getName()
    if ls[n].isDirectory() then
      addDir(out, dir .. name .. "/", ls[n])
     else
      local lname = name:lower()
      if lname:find("%.jpe?g$") or lname:find("%.png$") or lname:find("%.webp$")
        or lname:find("%.bmp$") or lname:find("%.gif$") then
        table.insert(out, dir .. name)
      end
    end
  end
end

checks.src = function()
  local src = {}
  addDir(src, "", File(luadir))
  return src
end

fd_list.onItemClick = function(l, v, p, i)
  fd_dlg.dismiss()
  local fd = tostring(v.Text)
  if string.find(fd, " = ") then
    fd = fd:gsub("% = .*", "")
  end
  if checks[fd] then
    if type(checks[fd]) == "table" then
      check_title.setText(fd)
      method.adapter(check_list, checks[fd])
      check_dlg.show()
     else
      check_title.setText(fd)
      method.adapter(check_list, checks[fd](fd))
      check_dlg.show()
    end
   else
    func[fd]()
  end
end

--子视图列表对话框
cd_dlg = MyBottomSheetDialog(activity).setView("dialog_item")
cd_list, cd_title = mDialogListView, mDialogTitle
cd_list.onItemClick = function(l, v, p, i)
  getCurr(chids[p])
  cd_dlg.dismiss()
end

--可选属性对话框
check_dlg = MyBottomSheetDialog(activity).setView("dialog_item")
check_list, check_title = mDialogListView, mDialogTitle
check_list.onItemClick = function(l, v, p, i)
  local v = tostring(v.text)
  if #v == 0 or v == "none" then
    v = nil
   elseif v == "Fixed size..." then
    check_dlg.dismiss()
    func[check_title.Text]()
    return
  end
  local fld = check_title.Text
  local old = curr[tostring(fld)]
  curr[tostring(fld)] = v
  check_dlg.dismiss()
  local s, l = pcall(loadlayout2, layout_main, {})
  if s then
    method.showlayout(l)
   else
    curr[tostring(fld)] = old
    print(l)
    Error(l)
  end
end

func = {}
func["添加"] = function()
  add_title.setText(tostring(currView.Class.getSimpleName()))
  for n = 0, #ns - 1 do
    if n ~= i then
      el.collapseGroup(n)
    end
  end
  add_dlg.show()
end

func["删除"] = function()
  local gp = currView.Parent.Tag
  if gp == nil then
    print("顶部控件可能不会被删除")
    return
  end
  for k, v in ipairs(gp) do
    if v == curr then
      table.remove(gp, k)
      break
    end
  end
  method.showlayout(loadlayout2(layout_main, {}))
end

func["父控件"] = function()
  local p = currView.Parent
  if p.Tag == nil then
    print("已经是顶部控件")
   else
    getCurr(p)
  end
end

chids = {}
func["子控件"] = function()
  chids = {}
  local arr = {}
  for n = 0, currView.ChildCount - 1 do
    local chid = currView.getChildAt(n)
    chids[n] = chid
    table.insert(arr, chid.Class.getSimpleName())
  end
  cd_title.setText(tostring(currView.Class.getSimpleName()))
  method.adapter(cd_list, arr)
  cd_dlg.show()
end

--添加视图对话框
add_dlg = MyBottomSheetDialog(activity).setView("dialog_expandablelist")
el, add_title = mDialogListView, mDialogTitle

local mAdapter = ArrayExpandableListAdapter(activity)

-- 类名与中文显示名合并: 按 wds 长度迭代(两表以本表为准), 任一侧缺失都容错,
-- 避免 wds/wds2 长短不一致时 "attempt to concatenate a nil value" 崩溃
for k, v in ipairs(ns) do
  local src, src2 = wds[k], wds2[k]
  local dst = {}
  for i = 1, #src do
    local e = src[i]
    if e then
      local cn = src2 and src2[i] or nil
      dst[i] = cn and (e .. " - " .. cn) or e
    end
  end
  ns[k] = ns2[k] or ""
  mAdapter.add(ns[k], dst)
end

el.setAdapter(mAdapter)

el.onChildClick = function(l, v, g, c)
  local name = wds[g + 1] and wds[g + 1][c + 1]
  local cls = name and _G[name] or nil
  -- 三方控件可能尚未注册:按需经 dex 装载器懒解析
  if cls == nil and name and _G.__lfThirdParty and _G.__lfThirdParty.map[name] then
    cls = _G.__lfThirdParty.resolve(_G.__lfThirdParty.map[name])
  end
  if cls == nil then
    Error("无法解析控件类: " .. tostring(name))
    return
  end
  local w = { cls }
  table.insert(curr, w)
  local s, l = pcall(loadlayout2, layout_main, {})
  if s then
    method.showlayout(l)
   else
    table.remove(curr)
    print(l)
    Error(l)
  end
  add_dlg.dismiss()
end

local function createEditDialog(k)
  local ids = {}
  local dialog = MaterialAlertDialogBuilder(activity)
  dialog.setView(loadlayout("dialog_fileinput", ids))

  ids.content.setText(curr[k] or "")

  dialog.setPositiveButton("确定", function()
    local v = tostring(ids.content.Text)
    if #v == 0 then
      v = nil
    end
    local old = curr[tostring(k)]
    curr[tostring(k)] = v
    local s, l = pcall(loadlayout2, layout_main, {})
    if s then
      method.showlayout(l)
     else
      curr[tostring(k)] = old
      print(l)
      Error(l)
    end
  end)

  dialog.setNegativeButton("取消", nil)

  dialog.setNeutralButton("无", function()
    local old = curr[tostring(k)]
    curr[tostring(k)] = nil
    local s, l = pcall(loadlayout2, layout_main, {})
    if s then
      method.showlayout(l)
     else
      curr[tostring(k)] = old
      print(l)
      Error(l)
    end
  end)

  return dialog, ids
end

setmetatable(func, {
  __index = function(t, k)
    return function()
      local dialog, ids = createEditDialog(k)
      dialog.setTitle(k)
      dialog.show()
    end
  end
})

local function save()
  local newLayoutStr = method.dumplayout(layout_main)
  local resultIntent = luajava.newInstance("android.content.Intent")
  resultIntent.putExtra("layout_result", newLayoutStr)
  activity.setResult(activity.RESULT_OK, resultIntent)
  activity.finish()
end

function onCreateOptionsMenu(menu)
  menu.add("保存")
  .setShowAsAction(2)
  .setIcon(bindClass "com.luaforge.studio.R".drawable.ic_content_save_outline)
  .onMenuItemClick = function()
    save()
  end
end

function onOptionsItemSelected(item)
  local id = item.getItemId()
  if id == android.R.id.home then
    save()
  end
end

function onKeyDown(e)
  if e == 4 then
    activity.setResult(activity.RESULT_CANCELED)
    activity.finish()
    return true
  end
end