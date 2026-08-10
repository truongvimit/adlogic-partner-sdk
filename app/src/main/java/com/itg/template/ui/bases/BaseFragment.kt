package com.itg.template.ui.bases

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment

abstract class BaseFragment<VB : ViewDataBinding> : Fragment() {
    lateinit var mBinding: VB

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutView = getLayoutFragment()
        mBinding = DataBindingUtil.inflate(inflater, layoutView, container, false)
        return mBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()
        onResizeViews()
        onClickViews()
        observerData()
    }

    abstract fun getLayoutFragment(): Int

    open fun initViews() {}

    open fun onClickViews() {}

    open fun onResizeViews() {}

    open fun observerData() {}
}