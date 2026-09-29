package io.onboardkit.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** A goal the user picked on the Goal or Welcome Back screen: the option's id and shown title. */
@Parcelize
data class GoalAnswer(val id: String, val title: String) : Parcelable
