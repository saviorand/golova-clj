(ns golova.ui.antd
  "Thin Reagent wrappers around antd components.
  Each wrapper converts ClojureScript props to JS and adapts the React
  component for use in Reagent hiccup."
  (:require ["antd" :as antd-js]
            ["@ant-design/icons" :as icons]
            [reagent.core :as r]
            [goog.object :as gobj]))

;; Flat js-object of raw React components, for use with Reagent's :> interop:
;;   [:> (.-Modal antd-js/antd) #js {:open true ...} children]
;; Sub-components like Form.Item are re-exposed under flat names (FormItem).
(def antd
  #js {:Alert                antd-js/Alert
       :AutoComplete         antd-js/AutoComplete
       :Badge                antd-js/Badge
       :Breadcrumb           antd-js/Breadcrumb
       :Button               antd-js/Button
       :Card                 antd-js/Card
       :Checkbox             antd-js/Checkbox
       :Collapse             antd-js/Collapse
       :CollapsePanel        (gobj/get antd-js/Collapse "Panel")
       :ConfigProvider       antd-js/ConfigProvider
       :Descriptions         antd-js/Descriptions
       :DescriptionsItem     (gobj/get antd-js/Descriptions "Item")
       :Divider              antd-js/Divider
       :Drawer               antd-js/Drawer
       :Dropdown             antd-js/Dropdown
       :Empty                antd-js/Empty
       :Flex                 antd-js/Flex
       :FloatButton          antd-js/FloatButton
       :Form                 antd-js/Form
       :FormItem             (gobj/get antd-js/Form "Item")
       :Input                antd-js/Input
       :InputSearch          (gobj/get antd-js/Input "Search")
       :InputTextArea        (gobj/get antd-js/Input "TextArea")
       :Layout               antd-js/Layout
       :LayoutContent        (gobj/get antd-js/Layout "Content")
       :LayoutHeader         (gobj/get antd-js/Layout "Header")
       :LayoutSider          (gobj/get antd-js/Layout "Sider")
       :List                 antd-js/List
       :ListItem             (gobj/get antd-js/List "Item")
       :ListItemMeta         (gobj/get (gobj/get antd-js/List "Item") "Meta")
       :Menu                 antd-js/Menu
       :Modal                antd-js/Modal
       :Popconfirm           antd-js/Popconfirm
       :Popover              antd-js/Popover
       :Progress             antd-js/Progress
       :Radio                antd-js/Radio
       :RadioGroup           (gobj/get antd-js/Radio "Group")
       :Segmented            antd-js/Segmented
       :Select               antd-js/Select
       :Space                antd-js/Space
       :Spin                 antd-js/Spin
       :Statistic            antd-js/Statistic
       :Switch               antd-js/Switch
       :Table                antd-js/Table
       :Tabs                 antd-js/Tabs
       :Tag                  antd-js/Tag
       :Tooltip              antd-js/Tooltip
       :Typography           antd-js/Typography
       :TypographyParagraph  (gobj/get antd-js/Typography "Paragraph")
       :TypographyText       (gobj/get antd-js/Typography "Text")
       :TypographyTitle      (gobj/get antd-js/Typography "Title")
       :Watermark            antd-js/Watermark})

;; ---------------------------------------------------------------------------
;; Helpers

(defn ->js
  "Deep-convert a ClojureScript map to a JS object.
  Keyword keys become string keys; values are recursively converted.
  Functions pass through unchanged."
  [m]
  (clj->js m :keyword-fn (comp str #(subs % 1))))

(defn- adapt-props
  "Convert a CLJS props map to a JS object suitable for a React component.
  Handles :on-click → :onClick, :class → :className, etc."
  [props]
  (when props
    (clj->js props :keyword-fn name)))

(defn- r-adapt
  "Create a Reagent component that wraps a React component.
  Props are converted from CLJS maps to JS objects."
  [component]
  (fn [props & children]
    (let [js-props (if (map? props) (adapt-props props) #js {})]
      (if (seq children)
        (into [:> component js-props] children)
        [:> component js-props]))))

;; ---------------------------------------------------------------------------
;; Layout

(def Layout (r-adapt antd-js/Layout))
(def LayoutHeader (r-adapt (gobj/get antd-js/Layout "Header")))
(def LayoutSider (r-adapt (gobj/get antd-js/Layout "Sider")))
(def LayoutContent (r-adapt (gobj/get antd-js/Layout "Content")))

;; ---------------------------------------------------------------------------
;; Navigation

(def Menu (r-adapt antd-js/Menu))
(def Breadcrumb (r-adapt antd-js/Breadcrumb))
(def Dropdown (r-adapt antd-js/Dropdown))

;; ---------------------------------------------------------------------------
;; Data Display

(def Card (r-adapt antd-js/Card))
(def Tag (r-adapt antd-js/Tag))
(def Badge (r-adapt antd-js/Badge))
(def Descriptions (r-adapt antd-js/Descriptions))
(def DescriptionsItem (r-adapt (gobj/get antd-js/Descriptions "Item")))
(def Table (r-adapt antd-js/Table))
(def Collapse (r-adapt antd-js/Collapse))
(def CollapsePanel (r-adapt (gobj/get antd-js/Collapse "Panel")))
(def Empty (r-adapt antd-js/Empty))
(def Tooltip (r-adapt antd-js/Tooltip))
(def Popover (r-adapt antd-js/Popover))
(def Statistic (r-adapt antd-js/Statistic))
(def Tabs (r-adapt antd-js/Tabs))
(def List (r-adapt antd-js/List))
(def ListItem (r-adapt (gobj/get antd-js/List "Item")))
(def ListItemMeta (r-adapt (gobj/get (gobj/get antd-js/List "Item") "Meta")))

;; ---------------------------------------------------------------------------
;; Data Entry

(def Input (r-adapt antd-js/Input))
(def InputSearch (r-adapt (gobj/get antd-js/Input "Search")))
(def InputTextArea (r-adapt (gobj/get antd-js/Input "TextArea")))
(def Select (r-adapt antd-js/Select))
(def Checkbox (r-adapt antd-js/Checkbox))
(def Radio (r-adapt antd-js/Radio))
(def RadioGroup (r-adapt (gobj/get antd-js/Radio "Group")))
(def Form (r-adapt antd-js/Form))
(def FormItem (r-adapt (gobj/get antd-js/Form "Item")))
(def Switch (r-adapt antd-js/Switch))
(def AutoComplete (r-adapt antd-js/AutoComplete))

;; ---------------------------------------------------------------------------
;; Feedback

(def Modal (r-adapt antd-js/Modal))
(def Drawer (r-adapt antd-js/Drawer))
(def Message (.-message antd-js))
(def notification (.-notification antd-js))
(def Alert (r-adapt antd-js/Alert))
(def Spin (r-adapt antd-js/Spin))
(def Progress (r-adapt antd-js/Progress))
(def Popconfirm (r-adapt antd-js/Popconfirm))

;; ---------------------------------------------------------------------------
;; General

(def Button (r-adapt antd-js/Button))
(def FloatButton (r-adapt antd-js/FloatButton))
(def Typography (r-adapt antd-js/Typography))
(def TypographyTitle (r-adapt (gobj/get antd-js/Typography "Title")))
(def TypographyText (r-adapt (gobj/get antd-js/Typography "Text")))
(def TypographyParagraph (r-adapt (gobj/get antd-js/Typography "Paragraph")))
(def Space (r-adapt antd-js/Space))
(def Flex (r-adapt antd-js/Flex))
(def Divider (r-adapt antd-js/Divider))
(def Segmented (r-adapt antd-js/Segmented))
(def Watermark (r-adapt antd-js/Watermark))

;; ---------------------------------------------------------------------------
;; ConfigProvider (for theming)

(def ConfigProvider (r-adapt antd-js/ConfigProvider))

;; ---------------------------------------------------------------------------
;; Icons

(def SearchOutlined (r-adapt icons/SearchOutlined))
(def PlusOutlined (r-adapt icons/PlusOutlined))
(def SettingOutlined (r-adapt icons/SettingOutlined))
(def HomeOutlined (r-adapt icons/HomeOutlined))
(def MenuOutlined (r-adapt icons/MenuOutlined))
(def CloseOutlined (r-adapt icons/CloseOutlined))
(def DeleteOutlined (r-adapt icons/DeleteOutlined))
(def EditOutlined (r-adapt icons/EditOutlined))
(def SaveOutlined (r-adapt icons/SaveOutlined))
(def SyncOutlined (r-adapt icons/SyncOutlined))
(def DownOutlined (r-adapt icons/DownOutlined))
(def RightOutlined (r-adapt icons/RightOutlined))
(def LeftOutlined (r-adapt icons/LeftOutlined))
(def UpOutlined (r-adapt icons/UpOutlined))
(def FileOutlined (r-adapt icons/FileOutlined))
(def FolderOutlined (r-adapt icons/FolderOutlined))
(def DatabaseOutlined (r-adapt icons/DatabaseOutlined))
(def CodeOutlined (r-adapt icons/CodeOutlined))
(def QuestionCircleOutlined (r-adapt icons/QuestionCircleOutlined))
(def InfoCircleOutlined (r-adapt icons/InfoCircleOutlined))
(def ExclamationCircleOutlined (r-adapt icons/ExclamationCircleOutlined))
(def CheckCircleOutlined (r-adapt icons/CheckCircleOutlined))
(def WarningOutlined (r-adapt icons/WarningOutlined))
(def ThunderboltOutlined (r-adapt icons/ThunderboltOutlined))
(def NodeIndexOutlined (r-adapt icons/NodeIndexOutlined))
(def ApiOutlined (r-adapt icons/ApiOutlined))
(def BranchesOutlined (r-adapt icons/BranchesOutlined))
(def AppstoreOutlined (r-adapt icons/AppstoreOutlined))
(def UnorderedListOutlined (r-adapt icons/UnorderedListOutlined))
(def EllipsisOutlined (r-adapt icons/EllipsisOutlined))
(def ImportOutlined (r-adapt icons/ImportOutlined))
(def ExportOutlined (r-adapt icons/ExportOutlined))
(def CloudSyncOutlined (r-adapt icons/CloudSyncOutlined))
(def KeyOutlined (r-adapt icons/KeyOutlined))
(def ReloadOutlined (r-adapt icons/ReloadOutlined))
(def StarOutlined (r-adapt icons/StarOutlined))
(def StarFilled (r-adapt icons/StarFilled))
(def TableOutlined (r-adapt icons/TableOutlined))
(def FormOutlined (r-adapt icons/FormOutlined))
(def OrderedListOutlined (r-adapt icons/OrderedListOutlined))
(def ProfileOutlined (r-adapt icons/ProfileOutlined))
(def ColumnHeightOutlined (r-adapt icons/ColumnHeightOutlined))
(def LinkOutlined (r-adapt icons/LinkOutlined))
(def SwapOutlined (r-adapt icons/SwapOutlined))
(def RetweetOutlined (r-adapt icons/RetweetOutlined))
