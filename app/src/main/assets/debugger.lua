-- 调试浮窗(改编自 Aqora 的 debugger.lua,适配 LuaForge-Studio)
-- 原始脚本:/storage/emulated/0/debugger.lua
-- 适配点:
--   1. 移除 lambda 语法(本项目 lambda 为保留字),类系统改用普通函数实现
--   2. 不再替换全局 error()(会破坏 pcall/assert 语义),仅接管全局 onError
--   3. 浮窗按悬浮窗权限选择 TYPE_APPLICATION_OVERLAY,无权限时回退挂 Activity 根视图
--   4. 暴露 __lfDebugger / __lfDebuggerCount / __lfDebuggerDump / __lfDebuggerClear 供 IDE 与 MCP 读取
--   5. 颜色优先读项目 Colors 模块,失败回退内置默认色

local SimpleClass = function (t)
  return setmetatable({}, {
    __call = function (_, context)
      local object = setmetatable({}, {
        __index = function (self, key)
          local member = t[key]
          if type(member) == "function" then
            -- 同时兼容点号 self.m(args) 与冒号 self:m(args) 两种调用:
            -- 冒号调用时首参即对象自身,此时丢弃它再前置注入 self。
            return function (first, ...)
              if first == self then
                return member(self, ...)
              end
              return member(self, first, ...)
            end
          end
          return member
        end
      })
      if type(t.new) == "function" then
        t.new(object, context)
      end
      return object
    end
  })
end

local bindClass = luajava.bindClass
local newInstance = luajava.newInstance
local android_R = bindClass "android.R"
local checkContext = function (c)
  assert(c, "The context of params could not be empty.")
end

local MaterialAlertDialogBuilder = bindClass "com.google.android.material.dialog.MaterialAlertDialogBuilder"
local AlertDialog = bindClass "androidx.appcompat.app.AlertDialog"

local showSthWithDialog = function (context, dialogTitle, sth)
  return MaterialAlertDialogBuilder(context)
    .setTitle(dialogTitle)
    .setItems(sth, {
      onClick = function (dialog, pos)
        MaterialAlertDialogBuilder(context)
          .setMessage(sth[pos + 1])
          .show()
          .findViewById(android_R.id.message)
          .setTextIsSelectable(true)
      end
    })
    .setPositiveButton(android_R.string.ok, nil)
    .setNegativeButton(android_R.string.cancel, nil)
    .show()
end

local SpannableString = bindClass "android.text.SpannableString"
local Typeface = bindClass "android.graphics.Typeface"
local StyleSpan = bindClass "android.text.style.StyleSpan"
local ForegroundColorSpan = bindClass "android.text.style.ForegroundColorSpan"
local Spanned = bindClass "android.text.Spanned"

-- 颜色:优先用项目的 Colors 模块(lua/Colors.lua),失败回退内置默认
local Colors
do
  local ok, mod = pcall(require, "Colors")
  if ok and type(mod) == "table" then
    Colors = mod
  else
    Colors = { colorPrimary = 0xFF6750A4, colorBackground = 0xFFFFFFFF }
  end
end

-- 安全取色:Colors 的 __index 会走 MaterialColors.getColor,可能抛错;失败回退默认值
local function themeColor(name, fallback)
  local ok, c = pcall(function () return Colors[name] end)
  if ok and type(c) == "number" then
    return c
  end
  return fallback
end

local PRINTS_ID = 0
local VARIABLES_ID = 1
local VARIABLE_TYPE_COLOUR = 0xff3D374E
-- 原版配色:常态胶囊 = Colors.colorPrimary,报错 = 0xffAA3437,文字 = Colors.colorBackground
local PILL_COLOR_ERROR = 0xffAA3437
local DEFAULT_PRIMARY = 0xFF6750A4
-- 浮窗 / 弹窗圆角半径(dp)
local CORNER_RADIUS_DP = 20

local GradientDrawable = bindClass "android.graphics.drawable.GradientDrawable"

-- 生成圆角矩形 drawable
local function roundedDrawable(radiusPx)
  local d = GradientDrawable()
  d.setShape(GradientDrawable.RECTANGLE)
  d.setCornerRadius(radiusPx)
  return d
end

-- 把 PopupMenu 弹窗背景换成圆角矩形:
--   appcompat 的 mPopup 字段指向 MenuPopupHelper,其 setBackgroundDrawable 能圆角整个弹窗;
--   同时给内部 ListView 铺一层圆角背景兜底。任一环节失败都静默保持默认外观,不影响功能。
local function roundPopup(popup, radiusPx)
  pcall(function ()
    local field = popup.getClass().getDeclaredField("mPopup")
    field.setAccessible(true)
    local inner = field.get(popup)
    pcall(function () inner.setBackgroundDrawable(roundedDrawable(radiusPx)) end)
    pcall(function ()
      local lv = inner.getListView()
      if lv then
        lv.setBackground(roundedDrawable(radiusPx))
        lv.setDividerHeight(0)
      end
    end)
  end)
end

local Debugger = SimpleClass {
  ICON_MODE_INFO = 0,
  ICON_MODE_ERROR = 1,
  prints = {},

  new = function (self, context)
    checkContext(context)
    self.context = context

    local Context = bindClass "android.content.Context"
    local WindowManager = bindClass "android.view.WindowManager"
    local PixelFormat = bindClass "android.graphics.PixelFormat"
    local Gravity = bindClass "android.view.Gravity"

    self.windowService = context.getSystemService(Context.WINDOW_SERVICE)
    self.windowLayoutParams = WindowManager.LayoutParams()
    self.windowLayoutParams.format = PixelFormat.RGBA_8888
    self.windowLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    self.windowLayoutParams.gravity = Gravity.LEFT | Gravity.TOP
    local metrics = context.getResources().getDisplayMetrics()
    self.windowLayoutParams.x = math.floor(metrics.widthPixels / 1.4)
    self.windowLayoutParams.y = math.floor(metrics.heightPixels / 5)
    self.windowLayoutParams.width = -2
    self.windowLayoutParams.height = -2

    local tostring = tostring
    local select = select

    self.old_print = _G.print
    self.old_onError = _G.onError

    -- 接管全局 print:所有 print 输出都汇入调试浮窗缓冲。
    -- sendMsg 仅用于落盘 luaforge.log;宿主在 debuggerActive 期间不会再在屏幕弹 Toast。
    _G.print = function (...)
      local n = select("#", ...)
      local buf = ""
      for i = 1, n do
        buf = buf .. tostring((select(i, ...)))
        if i < n then
          buf = buf .. "    "
        end
      end
      self:addPrint(buf)
      pcall(function ()
        context.sendMsg(buf)
      end)
    end

    local MyOnError = function (err, content)
      if content then
        self:addPrint(err .. "\t\t" .. tostring(content))
      else
        self:addPrint(tostring(err))
      end
      pcall(function ()
        self:setTextMode(self.ICON_MODE_ERROR)
      end)
    end

    -- 仅接管全局 onError(LuaActivity.sendError 会调用它);不改写全局 error()
    _G.onError = function (...)
      MyOnError(...)
      if self.old_onError then
        pcall(self.old_onError, ...)
      end
    end

    -- 告知宿主:调试浮窗已接管 print / onError,抑制旧的屏幕 Toast 回显
    pcall(function ()
      context.setDebuggerActive(true)
    end)
  end,

  destroy = function (self)
    if self.old_print then
      _G.print = self.old_print
      self.old_print = nil
    end
    if self.old_onError then
      _G.onError = self.old_onError
      self.old_onError = nil
    end
    pcall(function ()
      if self.context then
        self.context.setDebuggerActive(false)
      end
    end)
    pcall(function ()
      self:removeFloatWindow()
    end)
    self.context = nil
    self.windowService = nil
    self.windowLayoutParams = nil
    self.popup = nil
  end,

  setTextMode = function (self, mode)
    local color, content
    if mode == self.ICON_MODE_ERROR then
      color = PILL_COLOR_ERROR
      content = "Erroring"
    elseif mode == self.ICON_MODE_INFO then
      color = themeColor("colorPrimary", DEFAULT_PRIMARY)
      content = "Console"
    else
      error("The icon mode is not exist")
    end
    -- 底色放在圆角卡片上、文字区透明,保证圆角裁剪生效且配色随模式切换
    if self.floatLayout then
      pcall(function ()
        self.floatLayout.setCardBackgroundColor(color)
      end)
    end
    if self.textView then
      self.textView.text = content
    end
  end,

  showFloatWindow = function (self)
    local MaterialCardView = bindClass "com.google.android.material.card.MaterialCardView"
    local density = self.context.getResources().getDisplayMetrics().density
    local radiusPx = CORNER_RADIUS_DP * density

    local floatLayout = MaterialCardView(self.context)
    floatLayout.setRadius(radiusPx)
    floatLayout.cardElevation = 8 * density
    floatLayout.strokeWidth = 0
    self.floatLayout = floatLayout

    local textView = newInstance("androidx.appcompat.widget.AppCompatTextView", self.context)
    textView.setPadding(
      math.floor(38 * density), math.floor(18 * density),
      math.floor(38 * density), math.floor(18 * density))
    textView.textColor = themeColor("colorBackground", 0xFFFFFFFF)
    textView.backgroundColor = 0
    floatLayout.addView(textView)

    self.textView = textView
    self:setTextMode(self.ICON_MODE_INFO)

    local Build = bindClass "android.os.Build"
    local Settings = bindClass "android.provider.Settings"
    local WindowManager = bindClass "android.view.WindowManager"

    local canOverlay = (Build.VERSION.SDK_INT < 23) or Settings.canDrawOverlays(self.context)
    if canOverlay then
      if Build.VERSION.SDK_INT >= 26 then
        self.windowLayoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else
        self.windowLayoutParams.type = WindowManager.LayoutParams.TYPE_PHONE
      end
      self.windowService.addView(floatLayout, self.windowLayoutParams)
      self.useWindowManager = true
    else
      local FrameLayout = bindClass "android.widget.FrameLayout"
      local lp = FrameLayout.LayoutParams(-2, -2)
      lp.leftMargin = math.floor(self.windowLayoutParams.x)
      lp.topMargin = math.floor(self.windowLayoutParams.y)
      self.context.getWindow().getDecorView().addView(floatLayout, lp)
      self.useWindowManager = false
    end
    local startX, startY, wmX, wmY

    floatLayout.setOnTouchListener {
      onTouch = function (v, event)
        local action = event.getAction()
        if action == 0 then
          startX = event.getRawX()
          startY = event.getRawY()
          wmX = self.windowLayoutParams.x
          wmY = self.windowLayoutParams.y
        elseif action == 2 then
          self.windowLayoutParams.x = wmX + (event.getRawX() - startX)
          self.windowLayoutParams.y = wmY + (event.getRawY() - startY)
          if self.useWindowManager then
            self.windowService.updateViewLayout(self.floatLayout, self.windowLayoutParams)
          end
        end
        return false
      end
    }

    floatLayout.setOnClickListener {
      onClick = function (view)
        if self.popup then
          self.popup.show()
          return
        end

        local popup = newInstance("androidx.appcompat.widget.PopupMenu", self.context, view)
        local menu = popup.getMenu()
        local GROUP_ID = 0
        menu.add(GROUP_ID, PRINTS_ID, 0, "Show prints").onMenuItemClick = function ()
          self:showPrints()
        end
        menu.add(GROUP_ID, VARIABLES_ID, 1, "Show variables").onMenuItemClick = function ()
          self:showVariables()
        end
        roundPopup(popup, radiusPx)
        popup.show()
        self.popup = popup
      end
    }

    return self
  end,

  showPrints = function (self)
    local context = self.context
    local dialog = showSthWithDialog(context, "Prints", self.prints)

    local positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    positive.setText("Clear")
    positive.setOnClickListener {
      onClick = function (v)
        self.prints = {}
        dialog.dismiss()
      end
    }

    dialog.getListView().setSelection(#self.prints)
  end,

  showVariables = function (self)
    local type, tostring, utf8_len = type, tostring, utf8.len
    local variables = {}

    for _k, v in pairs(_ENV) do
      local _type = type(v)
      local spanned = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
      local spannable = SpannableString(_type .. " " .. tostring(v))
      spannable.setSpan(ForegroundColorSpan(VARIABLE_TYPE_COLOUR), 0, utf8_len(_type), spanned)
      spannable.setSpan(StyleSpan(Typeface.BOLD), 0, utf8_len(_type), spanned)
      variables[#variables + 1] = spannable
    end

    showSthWithDialog(self.context, "Variables", variables)
  end,

  readLog = function (self, s)
    local p = io.popen("logcat -d -v long " .. s)
    s = p:read("*a")
    p:close()
    s = s:gsub("%-+ beginning of[^\n]*\n", "")
    if #s == 0 then
      s = "<run the app to see its log output>"
    end
    return s
  end,

  addPrint = function (self, s)
    self.prints[#self.prints + 1] = s
  end,

  removeFloatWindow = function (self)
    local view = self.floatLayout
    if view then
      pcall(function () view.setOnTouchListener(nil) end)
      pcall(function () view.setOnClickListener(nil) end)

      if self.useWindowManager then
        pcall(function () self.windowService.removeView(view) end)
      else
        local parent = view.getParent()
        if parent then
          parent.removeView(view)
        end
      end

      self.floatLayout = nil
      self.textView = nil

      if self.popup then
        pcall(function () self.popup.dismiss() end)
        self.popup = nil
      end
    end
  end
}

return Debugger
