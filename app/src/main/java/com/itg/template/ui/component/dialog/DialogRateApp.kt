package com.itg.template.ui.component.dialog

import android.content.Context
import android.widget.Toast
import com.itg.template.R
import com.itg.template.databinding.DialogRateAppBinding
import com.itg.template.ui.bases.BaseDialog
import com.itg.template.ui.bases.ext.click


class DialogRateApp(context: Context, val onRating : () -> Unit) : BaseDialog<DialogRateAppBinding>(context,
    R.style.BaseDialog ) {

    override fun getLayoutDialog(): Int {
        return R.layout.dialog_rate_app
    }

    override fun onClickViews() {
        super.onClickViews()
        mBinding.btnLater.click {
            dismiss()
            val star = mBinding.simpleRatingBar.rating
            if (star <= 4) {
                Toast.makeText(context, context.getString(R.string.txt_thanks_you_for_rating), Toast.LENGTH_SHORT).show()
            } else {
                onRating.invoke()
            }
        }
    }
}