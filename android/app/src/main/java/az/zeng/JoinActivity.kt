package az.zeng

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Base: every screen follows the in-app language, not the system one. */
open class BaseActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(L.wrap(base))
}

class JoinActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_join)
        val name = findViewById<EditText>(R.id.name)
        val code = findViewById<EditText>(R.id.code)
        val err = findViewById<TextView>(R.id.error)
        val go = findViewById<Button>(R.id.go)

        go.setOnClickListener {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { err.setText(R.string.err_name); return@setOnClickListener }
            err.text = ""
            go.isEnabled = false
            Thread {
                val status = Signaling.join(this, n, code.text.toString().trim())
                runOnUiThread {
                    go.isEnabled = true
                    when (status) {
                        200 -> {
                            App.startService(this)
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        }
                        403 -> err.setText(R.string.err_code)
                        429 -> err.setText(R.string.err_many)
                        451 -> err.setText(R.string.banned)
                        else -> err.setText(R.string.err_net)
                    }
                }
            }.start()
        }
    }
}
