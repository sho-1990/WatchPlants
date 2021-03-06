package shsato.tk.watchplant.ui.activity

import android.app.Activity
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_camera2.*
import shsato.tk.watchplant.Logger
import shsato.tk.watchplant.R
import shsato.tk.watchplant.interfaces.CameraControl
import shsato.tk.watchplant.ui.fragment.Camera2Fragment

class Camera2Activity : AppCompatActivity() {

	private var mCameraControl: CameraControl? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_camera2)
		init(savedInstanceState)
	}


	override fun onOptionsItemSelected(item: MenuItem?): Boolean {
		item?.let {
			when (it.itemId) {
				android.R.id.home -> {
					back()
				}
			}
		}
		return super.onOptionsItemSelected(item)
	}

	override fun onBackPressed() {
		back()
	}

	private fun init(savedInstanceState: Bundle?) {
		val f = if (savedInstanceState == null) {
			val f = Camera2Fragment.newInstance()
			supportFragmentManager.beginTransaction()
					.replace(R.id.container, f)
					.commit()
			f
		} else {
			supportFragmentManager.findFragmentById(R.id.container)
		}
		if (f is CameraControl) {
			mCameraControl = f
		}

		setSupportActionBar(toolbar)
		supportActionBar?.let {
			it.setDisplayHomeAsUpEnabled(true)
			it.title = ""
		}

		take_picture.setOnClickListener {
			mCameraControl?.takePicture {
				Logger.d("takePicture done! $it")
				val image = it?.acquireNextImage()
				image?.use {

				}
			}
		}

	}

	private fun back() {
		setResult(Activity.RESULT_CANCELED)
		finish()
	}
}
