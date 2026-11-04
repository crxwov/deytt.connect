package space.deytt.connect

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.glassButtonBackground
import space.deytt.connect.DeyttUi.glassRowBackground
import space.deytt.connect.DeyttUi.liquidGlassButton
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.pressFeedback
import space.deytt.connect.DeyttUi.ripple
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.typeface

/**
 * App-owned dialogs sharing the authentic Liquid Glass surfaces,
 * specular edge lighting, and tactile pill buttons.
 */
object AppDialog {
    class Builder(private val host: Activity) : AlertDialog.Builder(host, R.style.DeyttDialogTheme) {
        private var dialogTitle: CharSequence? = null
        private var dialogMessage: CharSequence? = null
        private var dialogView: View? = null
        private var dialogItems: Array<out CharSequence>? = null
        private var dialogItemsListener: DialogInterface.OnClickListener? = null
        private var dialogSingleChoiceItems: Array<out CharSequence>? = null
        private var dialogCheckedItem: Int = -1
        private var dialogSingleChoiceListener: DialogInterface.OnClickListener? = null
        private var positiveLabel: CharSequence? = null
        private var positiveAction: DialogInterface.OnClickListener? = null
        private var negativeLabel: CharSequence? = null
        private var negativeAction: DialogInterface.OnClickListener? = null
        private var neutralLabel: CharSequence? = null
        private var neutralAction: DialogInterface.OnClickListener? = null

        override fun setTitle(title: CharSequence?): Builder {
            dialogTitle = title
            return this
        }

        override fun setTitle(titleId: Int): Builder {
            dialogTitle = host.getText(titleId)
            return this
        }

        override fun setMessage(message: CharSequence?): Builder {
            dialogMessage = message
            return this
        }

        override fun setMessage(messageId: Int): Builder {
            dialogMessage = host.getText(messageId)
            return this
        }

        override fun setView(view: View?): Builder {
            dialogView = view
            return this
        }

        override fun setItems(items: Array<out CharSequence>?, listener: DialogInterface.OnClickListener?): Builder {
            dialogItems = items
            dialogItemsListener = listener
            return this
        }

        override fun setItems(itemsId: Int, listener: DialogInterface.OnClickListener?): Builder {
            dialogItems = host.resources.getTextArray(itemsId)
            dialogItemsListener = listener
            return this
        }

        override fun setSingleChoiceItems(
            items: Array<out CharSequence>?,
            checkedItem: Int,
            listener: DialogInterface.OnClickListener?,
        ): Builder {
            dialogSingleChoiceItems = items
            dialogCheckedItem = checkedItem
            dialogSingleChoiceListener = listener
            return this
        }

        override fun setSingleChoiceItems(
            itemsId: Int,
            checkedItem: Int,
            listener: DialogInterface.OnClickListener?,
        ): Builder {
            dialogSingleChoiceItems = host.resources.getTextArray(itemsId)
            dialogCheckedItem = checkedItem
            dialogSingleChoiceListener = listener
            return this
        }

        override fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            positiveLabel = text
            positiveAction = listener
            return this
        }

        override fun setPositiveButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            positiveLabel = host.getText(textId)
            positiveAction = listener
            return this
        }

        override fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            negativeLabel = text
            negativeAction = listener
            return this
        }

        override fun setNegativeButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            negativeLabel = host.getText(textId)
            negativeAction = listener
            return this
        }

        override fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            neutralLabel = text
            neutralAction = listener
            return this
        }

        override fun setNeutralButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            neutralLabel = host.getText(textId)
            neutralAction = listener
            return this
        }

        override fun create(): AlertDialog {
            var createdDialog: AlertDialog? = null

            val root = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
                background = host.rounded(DeyttUi.SHEET_SURFACE, 26f)
                clipToOutline = true
            }

            // Subtitle tag
            root.addView(host.mono("./connect · ОКНО", 9f, DeyttUi.SKY, 650).apply {
                letterSpacing = .06f
                setPadding(0, 0, 0, host.dp(6))
            })

            // Title
            dialogTitle?.takeIf { it.isNotBlank() }?.let { titleText ->
                val titleView = TextView(host).apply {
                    text = titleText
                    textSize = 19f
                    setTextColor(DeyttUi.TEXT)
                    typeface = host.typeface(DeyttUi.FontFamily.INTER_TIGHT, 650)
                    letterSpacing = -.02f
                    setPadding(0, 0, 0, host.dp(8))
                }
                root.addView(titleView)
            }

            // Message
            dialogMessage?.takeIf { it.isNotBlank() }?.let { messageText ->
                val msgView = TextView(host).apply {
                    text = messageText
                    textSize = 13.5f
                    setTextColor(DeyttUi.MUTED)
                    typeface = host.typeface(DeyttUi.FontFamily.INTER_TIGHT, 400)
                    setLineSpacing(host.dp(3).toFloat(), 1f)
                    setPadding(0, 0, 0, host.dp(12))
                }
                root.addView(msgView)
            }

            // Custom View (if provided)
            dialogView?.let { custom ->
                (custom.parent as? ViewGroup)?.removeView(custom)
                root.addView(custom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = host.dp(10)
                })
            }

            // Items (List or Single-Choice)
            val items = dialogSingleChoiceItems ?: dialogItems
            val isSingleChoice = dialogSingleChoiceItems != null
            if (items != null && items.isNotEmpty()) {
                val itemsContainer = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                }
                var selectedIndex = dialogCheckedItem
                val itemViews = mutableListOf<LinearLayout>()

                items.forEachIndexed { index, itemText ->
                    val isSelected = isSingleChoice && index == selectedIndex
                    val row = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = host.dp(48)
                        setPadding(host.dp(16), host.dp(10), host.dp(16), host.dp(10))
                        background = host.glassRowBackground(isSelected, 16f)
                        isClickable = true
                        isFocusable = true
                        foreground = host.ripple(16f)
                        host.pressFeedback(this, .985f)

                        val label = TextView(host).apply {
                            text = itemText
                            textSize = 14f
                            setTextColor(if (isSelected) DeyttUi.SKY else DeyttUi.TEXT)
                            typeface = host.typeface(DeyttUi.FontFamily.INTER_TIGHT, if (isSelected) 600 else 450)
                            includeFontPadding = false
                        }
                        addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                        if (isSingleChoice) {
                            val dot = View(host).apply {
                                background = host.rounded(if (isSelected) DeyttUi.SKY else 0x22FFFFFF, 8f, if (isSelected) DeyttUi.SKY else DeyttUi.LINE)
                            }
                            addView(dot, LinearLayout.LayoutParams(host.dp(14), host.dp(14)).apply {
                                leftMargin = host.dp(10)
                            })
                        } else {
                            val chevron = TextView(host).apply {
                                text = "›"
                                textSize = 18f
                                setTextColor(DeyttUi.MUTED)
                                typeface = host.typeface(DeyttUi.FontFamily.INTER_TIGHT, 400)
                            }
                            addView(chevron, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                leftMargin = host.dp(10)
                            })
                        }

                        setOnClickListener {
                            if (isSingleChoice) {
                                selectedIndex = index
                                itemViews.forEachIndexed { i, itemView ->
                                    val sel = i == selectedIndex
                                    itemView.background = host.glassRowBackground(sel, 16f)
                                    val rowLabel = itemView.getChildAt(0) as? TextView
                                    rowLabel?.setTextColor(if (sel) DeyttUi.SKY else DeyttUi.TEXT)
                                    rowLabel?.typeface = host.typeface(DeyttUi.FontFamily.INTER_TIGHT, if (sel) 600 else 450)
                                    val rowDot = itemView.getChildAt(1)
                                    rowDot?.background = host.rounded(if (sel) DeyttUi.SKY else 0x22FFFFFF, 8f, if (sel) DeyttUi.SKY else DeyttUi.LINE)
                                }
                                dialogSingleChoiceListener?.onClick(createdDialog, index)
                            } else {
                                dialogItemsListener?.onClick(createdDialog, index)
                                createdDialog?.dismiss()
                            }
                        }
                    }

                    itemViews.add(row)
                    itemsContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (index > 0) topMargin = host.dp(7)
                    })
                }

                val scroll = ScrollView(host).apply {
                    isFillViewport = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(itemsContainer)
                }
                root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = host.dp(12)
                })
            }

            // Action Buttons
            val hasPositive = positiveLabel != null
            val hasNegative = negativeLabel != null
            val hasNeutral = neutralLabel != null

            if (hasPositive || hasNegative || hasNeutral) {
                val buttonBar = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, host.dp(4), 0, 0)
                }

                if (hasNeutral) {
                    val neutralBtn = TextView(host).apply {
                        text = neutralLabel
                        textSize = 13.5f
                        setTextColor(DeyttUi.TEXT)
                        typeface = host.typeface(DeyttUi.FontFamily.INTER_TIGHT, 600)
                        gravity = Gravity.CENTER
                        minHeight = host.dp(48)
                        setPadding(host.dp(14), host.dp(8), host.dp(14), host.dp(8))
                        background = host.liquidGlassButton(secondary = true, radius = 22f)
                        foreground = host.ripple(22f)
                        elevation = host.dp(1).toFloat()
                        host.pressFeedback(this, .985f)
                        setOnClickListener {
                            neutralAction?.onClick(createdDialog, DialogInterface.BUTTON_NEUTRAL)
                            createdDialog?.dismiss()
                        }
                    }
                    buttonBar.addView(neutralBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = host.dp(8)
                    })
                }

                if (hasNegative) {
                    val negativeBtn = TextView(host).apply {
                        text = negativeLabel
                        textSize = 13.5f
                        setTextColor(DeyttUi.MUTED)
                        typeface = host.typeface(DeyttUi.FontFamily.INTER_TIGHT, 560)
                        gravity = Gravity.CENTER
                        minHeight = host.dp(48)
                        setPadding(host.dp(14), host.dp(8), host.dp(14), host.dp(8))
                        background = host.liquidGlassButton(secondary = true, radius = 22f)
                        foreground = host.ripple(22f)
                        elevation = host.dp(1).toFloat()
                        host.pressFeedback(this, .985f)
                        setOnClickListener {
                            negativeAction?.onClick(createdDialog, DialogInterface.BUTTON_NEGATIVE)
                            createdDialog?.dismiss()
                        }
                    }
                    val lp = if (hasPositive) {
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = host.dp(8)
                        }
                    } else {
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    buttonBar.addView(negativeBtn, lp)
                }

                if (hasPositive) {
                    val positiveBtn = TextView(host).apply {
                        text = positiveLabel
                        textSize = 13.5f
                        setTextColor(Color.WHITE)
                        typeface = host.typeface(DeyttUi.FontFamily.INTER_TIGHT, 620)
                        gravity = Gravity.CENTER
                        minHeight = host.dp(48)
                        setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(8))
                        background = host.glassButtonBackground(DeyttUi.BLUE, DeyttUi.BLUE_DEEP, 22f)
                        foreground = host.ripple(22f)
                        elevation = host.dp(2).toFloat()
                        host.pressFeedback(this, .985f)
                        setOnClickListener {
                            positiveAction?.onClick(createdDialog, DialogInterface.BUTTON_POSITIVE)
                            createdDialog?.dismiss()
                        }
                    }
                    val lp = if (!hasNegative && !hasNeutral) {
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    } else {
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    buttonBar.addView(positiveBtn, lp)
                }

                root.addView(buttonBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            super.setView(root)

            val dialog = super.create()
            createdDialog = dialog

            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }

            dialog.setOnShowListener {
                dialog.window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setLayout(minOf(host.resources.displayMetrics.widthPixels - host.dp(32), host.dp(390)), ViewGroup.LayoutParams.WRAP_CONTENT)
                    setDimAmount(.65f)
                    DeyttUi.blurBehind(this, host.dp(24))
                }
            }

            return dialog
        }
    }
}
