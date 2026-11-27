package com.iqrax.companion.agent

import android.content.Context
import com.iqrax.companion.operator.HumanPhoneOperator

object CommandDispatcher {

    fun initTTS(context: Context) {
        HumanPhoneOperator.init(context)
    }

    fun speak(text: String) {
        HumanPhoneOperator.speak(text)
    }

    /**
     * Dispatches any user command directly to the Autonomous Human Phone Operator
     */
    fun dispatch(
        context: Context,
        rawCommand: String,
        onLog: ((String) -> Unit)? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        HumanPhoneOperator.execute(
            context = context,
            commandText = rawCommand,
            onStatus = { status -> onLog?.invoke(status) },
            onFinish = { success -> onComplete?.invoke(success) }
        )
    }
}
