package com.example.chatapp

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.firebase.ui.database.FirebaseRecyclerAdapter
import com.firebase.ui.database.FirebaseRecyclerOptions
import com.firebase.ui.database.SnapshotParser
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuth.getInstance
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.android.synthetic.main.activity_main.*


class MainActivity : AppCompatActivity(), OnConnectionFailedListener {
    private var mUsername: String? = null
    private var mPhotoUrl: String? = null
    private var mSharedPreferences: SharedPreferences? = null
    private var mGoogleApiClient: GoogleApiClient? = null
    private var mLinearLayoutManager: LinearLayoutManager? = null

    // Firebase instance variables
    private lateinit var mFirebaseAuth: FirebaseAuth
    private var mFirebaseUser: FirebaseUser? = null
    private lateinit var mFirebaseDatabaseReference: DatabaseReference
    private lateinit var mFirebaseAdapter: FirebaseRecyclerAdapter<FriendlyMessage, MessageViewHolder>
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        // Set default username is anonymous.
        mUsername = ANONYMOUS
        mGoogleApiClient = GoogleApiClient.Builder(this)
            .enableAutoManage(this /* FragmentActivity */, this /* OnConnectionFailedListener */)
            .addApi(Auth.GOOGLE_SIGN_IN_API)
            .build()

        // Initialize Firebase Analytics
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)

        // Initialize Firebase Auth
        mFirebaseAuth = getInstance()
        mFirebaseUser = mFirebaseAuth.currentUser
        if (mFirebaseUser == null) {
            // Not signed in, launch the Sign In activity
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
            return
        } else {
            mUsername = mFirebaseUser!!.displayName
            if (mFirebaseUser!!.photoUrl != null) {
                mPhotoUrl = mFirebaseUser!!.photoUrl.toString()
            }
        }

        mLinearLayoutManager = LinearLayoutManager(this)
        mLinearLayoutManager!!.stackFromEnd = true
        messageRecyclerView.layoutManager = mLinearLayoutManager

        // New child entries
        mFirebaseDatabaseReference = FirebaseDatabase.getInstance().reference

        val parser =
            SnapshotParser { snapshot ->
                val friendlyMessage = snapshot.getValue(FriendlyMessage::class.java)
                if (friendlyMessage != null) {
                    friendlyMessage.id = snapshot.key
                }
                friendlyMessage!!
            }

        val messagesRef = mFirebaseDatabaseReference.child(MESSAGES_CHILD)
        val options = object : FirebaseRecyclerOptions.Builder<FriendlyMessage>() {}
            .setQuery(messagesRef, parser).build()

        mFirebaseAdapter =
            object : FirebaseRecyclerAdapter<FriendlyMessage, MessageViewHolder>(options) {
                override fun onCreateViewHolder(
                    parent: ViewGroup,
                    viewType: Int
                ): MessageViewHolder {
                    val inflater = LayoutInflater.from(parent.context)
                    return MessageViewHolder(inflater.inflate(R.layout.item_message, parent, false))
                }

                override fun onBindViewHolder(
                    viewHolder: MessageViewHolder,
                    position: Int,
                    friendlyMessage: FriendlyMessage
                ) {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    if (friendlyMessage.text != null) {
                        viewHolder.messageTextView.text = friendlyMessage.text
                        viewHolder.messageTextView.visibility = TextView.VISIBLE
                        viewHolder.messageImageView.visibility = ImageView.GONE
                    } else if (friendlyMessage.imageUrl != null) {
                        val imageUrl = friendlyMessage.imageUrl
                        if (imageUrl!!.startsWith("gs://")) {
                            val storageReference = FirebaseStorage.getInstance()
                                .getReferenceFromUrl(imageUrl)
                            storageReference.downloadUrl.addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    val downloadUrl = task.result.toString()
                                    Glide.with(viewHolder.messageImageView.context)
                                        .load(downloadUrl)
                                        .into(viewHolder.messageImageView)
                                } else {
                                    Log.w(
                                        TAG, "Getting download url was not successful.",
                                        task.exception
                                    )
                                }
                            }
                        } else {
                            Glide.with(viewHolder.messageImageView.context)
                                .load(friendlyMessage.imageUrl)
                                .into(viewHolder.messageImageView)
                        }
                        viewHolder.messageImageView.visibility = ImageView.VISIBLE
                        viewHolder.messageTextView.visibility = TextView.GONE
                    }

                    viewHolder.messengerTextView.text = friendlyMessage.name
                    if (friendlyMessage.photoUrl == null) {
                        viewHolder.messengerImageView.setImageDrawable(
                            ContextCompat.getDrawable(
                                this@MainActivity,
                                R.drawable.ic_account_circle_black_36dp
                            )
                        )
                    } else {
                        Glide.with(this@MainActivity)
                            .load(friendlyMessage.photoUrl)
                            .into(viewHolder.messengerImageView)
                    }

                }
            }
        mFirebaseAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                val friendlyMessageCount = mFirebaseAdapter.itemCount
                val lastVisiblePosition =
                    (messageRecyclerView.layoutManager as LinearLayoutManager?)?.findLastCompletelyVisibleItemPosition()
                // If the recycler view is initially being loaded or the
                // user is at the bottom of the list, scroll to the bottom
                // of the list to show the newly added message.
                if (lastVisiblePosition == -1 ||
                    (positionStart >= (friendlyMessageCount - 1) &&
                            lastVisiblePosition == (positionStart - 1))
                ) {
                    messageRecyclerView.scrollToPosition(positionStart)
                }
            }
        })

        messageRecyclerView.adapter = mFirebaseAdapter

        messageEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                charSequence: CharSequence,
                i: Int,
                i1: Int,
                i2: Int
            ) {

            }

            override fun onTextChanged(
                charSequence: CharSequence,
                i: Int,
                i1: Int,
                i2: Int
            ) {
                sendButton.isEnabled = charSequence.toString().trim { it <= ' ' }.isNotEmpty()
            }

            override fun afterTextChanged(editable: Editable) {}
        })

        sendButton.setOnClickListener {
            val friendlyMessage =
                FriendlyMessage(
                    messageEditText.text.toString(),
                    mUsername,
                    mPhotoUrl,
                    null /* no image */
                )
            mFirebaseDatabaseReference.child(MESSAGES_CHILD)
                .push().setValue(friendlyMessage)
            messageEditText.setText("")
            val bundle = Bundle()
            bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, "Send button")
            bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "button")
            firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle)
        }

        addMessageImageView.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            startActivityForResult(intent, REQUEST_IMAGE)
        }
    }

    override fun onResume() {
        mFirebaseAdapter.startListening()
        super.onResume()
    }

    override fun onPause() {
        mFirebaseAdapter.stopListening()
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.sign_out_menu -> {
                mFirebaseAuth.signOut()
                Auth.GoogleSignInApi.signOut(mGoogleApiClient)
                mUsername = ANONYMOUS
                startActivity(Intent(this, SignInActivity::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onConnectionFailed(connectionResult: ConnectionResult) { // An unresolvable error has occurred and Google APIs (including Sign-In) will not
// be available.
        Log.d(TAG, "onConnectionFailed:$connectionResult")
        Toast.makeText(this, "Google Play Services error.", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        if (requestCode == REQUEST_IMAGE) {
            if (resultCode == RESULT_OK) {
                if (data != null) {
                    val uri = data.data
                    Log.d(TAG, "Uri: " + uri.toString())

                    val tempMessage = FriendlyMessage(
                        null, mUsername, mPhotoUrl,
                        LOADING_IMAGE_URL
                    )
                    mFirebaseDatabaseReference.child(MESSAGES_CHILD).push()
                        .setValue(tempMessage, object : DatabaseReference.CompletionListener {
                            override fun onComplete(
                                databaseError: DatabaseError?,
                                databaseReference: DatabaseReference
                            ) {
                                if (databaseError == null) {
                                    val key = databaseReference.key
                                    val storageReference =
                                        FirebaseStorage.getInstance()
                                            .getReference(mFirebaseUser!!.uid)
                                            .child(key!!)
                                            .child(uri!!.lastPathSegment!!)

                                    putImageInStorage(storageReference, uri, key)
                                } else {
                                    Log.w(
                                        TAG, "Unable to write message to database.",
                                        databaseError.toException()
                                    )
                                }
                            }

                        })
                }
            }
        }
    }

    private fun putImageInStorage(storageReference: StorageReference, uri: Uri, key: String) {
        storageReference.putFile(uri).addOnCompleteListener(
            this
        ) { putFileTask ->
            if (putFileTask.isSuccessful) {
                putFileTask.result!!.metadata!!.reference!!.downloadUrl
                    .addOnCompleteListener(this@MainActivity
                    ) { downloadTask ->
                        if (downloadTask.isSuccessful) {
                            val friendlyMessage =
                                FriendlyMessage(
                                    null, mUsername, mPhotoUrl,
                                    downloadTask.result.toString()
                                )
                            mFirebaseDatabaseReference.child(MESSAGES_CHILD)
                                .child(key)
                                .setValue(friendlyMessage)
                        }
                    }
            } else {
                Log.w(
                    TAG, "Image upload task was not successful.",
                    putFileTask.exception
                )
            }
        }
    }





    companion object {

        private const val TAG = "MainActivity"
        const val MESSAGES_CHILD = "messages"
        private const val REQUEST_INVITE = 1
        private const val REQUEST_IMAGE = 2
        private const val LOADING_IMAGE_URL = "https://www.google.com/images/spin-32.gif"
        const val DEFAULT_MSG_LENGTH_LIMIT = 10
        const val ANONYMOUS = "anonymous"
        private const val MESSAGE_SENT_EVENT = "message_sent"
        private const val MESSAGE_URL = "http://friendlychat.firebase.google.com/message/"
    }
}

class MessageViewHolder(v: View?) : RecyclerView.ViewHolder(v!!) {
    var messageTextView: TextView
    var messageImageView: ImageView
    var messengerTextView: TextView
    var messengerImageView: CircleImageView

    init {
        messageTextView = itemView.findViewById(R.id.messageTextView)
        messageImageView =
            itemView.findViewById(R.id.messageImageView)
        messengerTextView = itemView.findViewById(R.id.messengerTextView)
        messengerImageView = itemView.findViewById(R.id.messengerImageView) as CircleImageView
    }
}
