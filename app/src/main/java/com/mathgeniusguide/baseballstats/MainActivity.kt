import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.MutableLiveData
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment.findNavController
import androidx.navigation.ui.onNavDestinationSelected
import androidx.navigation.ui.setupWithNavController
import com.bumptech.glide.Glide
import com.facebook.CallbackManager
import com.facebook.appevents.AppEventsLogger
import com.facebook.login.LoginManager
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.*
import com.mathgeniusguide.baseballstats.R
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity(), GoogleApiClient.OnConnectionFailedListener {
    val TAG = "Quick Notes"
    val ANONYMOUS = "anonymous"
    lateinit var navController: NavController
    var noteList = emptyList<Note>().toMutableList()
    var noteListSelected = emptyList<Note>().toMutableList()
    var noteSelected = Note.create()
    var tagList = emptyList<Tag>().toMutableList()
    var searchDescription = ""
    var firebaseLoaded: MutableLiveData<Boolean> = MutableLiveData()

    // Firebase variables
    lateinit var googleApiClient: GoogleApiClient
    lateinit var firebaseAuth: FirebaseAuth
    var firebaseUser: FirebaseUser? = null
    var username = ANONYMOUS
    private var useremail = ANONYMOUS
    var userkey = ""
    var photoUrl = ""
    lateinit var database: DatabaseReference
    var callbackManager: CallbackManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        // Facebook
        AppEventsLogger.activateApp(application);

        // Google
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleApiClient = GoogleApiClient.Builder(this)
            .enableAutoManage(this, this)
            .addApi(Auth.GOOGLE_SIGN_IN_API, gso)
            .build()

        firebaseLoaded.postValue(false)
        navController = findNavController(nav_host_fragment)
        toolbar.setupWithNavController(navController)
        tabs.setupWithNavController(navController)

        tabs.setOnNavigationItemSelectedListener {
            if (it.itemId == R.id.note) {
                noteSelected = Note.create()
                noteSelected.id = ""
                noteSelected.tags = ""
                noteSelected.time = ""
                noteSelected.content = ""
            }
            it.onNavDestinationSelected(navController)
        }

        val toggle = ActionBarDrawerToggle(
            this,
            drawer_layout,
            toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        )
        drawer_layout.addDrawerListener(toggle)
        toggle.syncState()

        drawer_view.setNavigationItemSelectedListener {
            it.onNavDestinationSelected(navController)
        }

        // activate drawer clicks
        drawer_view.setNavigationItemSelectedListener {
            drawer_layout.closeDrawer(GravityCompat.START)
            when (it.itemId) {
                R.id.logout -> logout()
                else -> it.onNavDestinationSelected(navController)
            }
        }

        // Firebase
        setUpFirebase()
    }

    private fun setUpFirebase() {
        // set up firebase login
        FirebaseApp.initializeApp(this);
        database = FirebaseDatabase.getInstance().reference
        database.orderByKey().addListenerForSingleValueEvent(itemListener)
        firebaseAuth = FirebaseAuth.getInstance()
        firebaseUser = firebaseAuth.currentUser
        login(firebaseUser)
    }

    var itemListener: ValueEventListener = object : ValueEventListener {
        override fun onDataChange(dataSnapshot: DataSnapshot) {
            // Get Post object and use the values to update the UI
            addDataToList(dataSnapshot)
        }

        override fun onCancelled(databaseError: DatabaseError) {
            // Getting Item failed, log a message
            Log.w(TAG, "loadItem:onCancelled", databaseError.toException())
        }
    }

    private fun addDataToList(dataSnapshot: DataSnapshot) {
        val notes = dataSnapshot.child(Constants.NOTES).child(firebaseUser?.uid ?: ANONYMOUS).children.iterator()
        noteList.clear()
        tagList.clear()
        while (notes.hasNext()) {
            val currentItem = notes.next()
            val note = Note.create()
            val map = currentItem.getValue() as HashMap<String, Any>
            note.id = currentItem.key
            note.time = map.get("time") as String?
            note.content = map.get("content") as String?
            note.tags = map.get("tags") as String?
            noteList.add(note)
            for (tag in (note.tags ?: "").split(",")) {
                tagList.add(Tag(tag, false))
            }
        }
        tagList = tagList.distinctBy { it.id }.filter { it.id.length <= 20 && it.id.isNotEmpty() }
            .toMutableList()
        tagList.sortBy { it.id }
        noteList.sortByDescending { it.time }
        firebaseLoaded.postValue(true)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.action_bar_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.login) {
            return logout()
        }
        Log.d(TAG, item.itemId.toString())
        return item.onNavDestinationSelected(navController) || super.onOptionsItemSelected(item)
    }

    override fun onConnectionFailed(connectionResult: ConnectionResult) {
        // An unresolvable error has occurred and Google APIs (including Sign-In) will not be available.
        Log.d(TAG, "onConnectionFailed:$connectionResult")
        Toast.makeText(this, "Google Play Services error.", Toast.LENGTH_SHORT).show()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = Auth.GoogleSignInApi.getSignInResultFromIntent(data)
        if (result != null) {
            if (result.isSuccess && result.signInAccount != null) {
                // Google Sign-In was successful, authenticate with Firebase
                firebaseAuthWithGoogle(result.signInAccount!!)
            } else {
                // Google Sign-In failed
                Log.e(TAG, "Google Sign-In failed.")
            }
        } else {
            callbackManager?.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun firebaseAuthWithGoogle(acct: GoogleSignInAccount) {
        Log.d(TAG, "firebaseAuthWithGooogle:" + acct.id!!)
        val credential = GoogleAuthProvider.getCredential(acct.idToken, null)
        firebaseAuth.signInWithCredential(credential)
            .addOnCompleteListener(this, { task ->
                Log.d(TAG, "signInWithCredential:onComplete:${task.isSuccessful}")

                // If sign in fails, display a message to the user. If sign in succeeds
                // the auth state listener will be notified and logic to handle the
                // signed in user can be handled in the listener.
                if (!task.isSuccessful) {
                    Log.w(TAG, "signInWithCredential", task.exception)
                    Toast.makeText(
                        this, "Authentication failed.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    login(firebaseAuth.currentUser)
                }
            })
    }

    private fun updateDisplayName(user: FirebaseUser?, displayName: String) {
        if (user != null) {
            val profileUpdates = UserProfileChangeRequest.Builder()
                .setDisplayName(displayName).build()
            user.updateProfile(profileUpdates)
        }
    }

    fun newUser(email: String, password: String, displayName: String) {
        firebaseAuth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    Log.d(TAG, "createUserWithEmail:success")
                    val user = firebaseAuth.currentUser
                    updateDisplayName(user, displayName)
                    login(user)
                } else {
                    // If sign in fails, display a message to the user.
                    Log.w(TAG, "createUserWithEmail:failure", task.exception)
                    Toast.makeText(baseContext, "Authentication failed.",
                        Toast.LENGTH_SHORT).show()
                }
            }
    }

    fun loginUser(email: String, password: String) {
        firebaseAuth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    Log.d(TAG, "signInWithEmail:success")
                    val user = firebaseAuth.currentUser
                    login(user)
                } else {
                    // If sign in fails, display a message to the user.
                    Log.w(TAG, "signInWithEmail:failure", task.exception)
                    Toast.makeText(baseContext, "Authentication failed.",
                        Toast.LENGTH_SHORT).show()
                }
            }
    }

    fun passwordReset(email: String) {
        if (email.isEmpty()) {
            Toast.makeText(this, resources.getString(R.string.email_required), Toast.LENGTH_LONG).show()
            return
        }
        firebaseAuth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                val message = resources.getString(if (task.isSuccessful) R.string.password_reset_email_sent else R.string.invalid_email)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
    }

    fun login(user: FirebaseUser?) {
        firebaseUser = user
        if (user != null) {
            // retrieve username and email from firebase
            username = user.displayName ?: ANONYMOUS
            useremail = user.email ?: ANONYMOUS
            // show username and email in drawer layout
            val header = drawer_view.getHeaderView(0)
            header.findViewById<TextView>(R.id.userName).text = username
            header.findViewById<TextView>(R.id.userEmail).text = useremail
            // show photo in drawer layout if user has one
            if (user.photoUrl != null) {
                photoUrl = user.photoUrl.toString()
                Glide.with(this).load(photoUrl).into(header.findViewById(R.id.userImage))
            }
            // retrieve data from firebase database
            database = FirebaseDatabase.getInstance().reference
            database.orderByKey().addListenerForSingleValueEvent(itemListener)
            // navigate to map fragment and make toolbars visible
            navController.navigate(R.id.action_login)
            tabs.visibility = View.VISIBLE
            toolbar.visibility = View.VISIBLE
            drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        }
    }

    private fun logout(): Boolean {
        val alert = AlertDialog.Builder(this)
        alert.setTitle(R.string.logout)
        alert.setMessage(R.string.logout_alert)
        alert.setPositiveButton(R.string.yes, DialogInterface.OnClickListener { dialog, which ->
            // sign out of firebase
            firebaseAuth.signOut()
            Auth.GoogleSignInApi.signOut(googleApiClient)
            LoginManager.getInstance().logOut()
            callbackManager = null
            // set user information to defaults
            username = ANONYMOUS
            firebaseUser = null
            photoUrl = ""
            navController.navigate(R.id.action_logout)
            tabs.visibility = View.GONE
            toolbar.visibility = View.GONE
            drawer_layout.closeDrawer(GravityCompat.START)
        })
        alert.setNegativeButton(R.string.no, null)
        alert.show()
        return true
    }
}