package com.example.notetify.activities;

import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView; // Added for no requests message
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

// Removed OnCompleteListener import as it's an interface, not a class to import directly for this usage
// import com.google.android.gms.tasks.OnCompleteListener;
// import com.google.android.gms.tasks.Task; // Not used directly here

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.example.notetify.R; // Adjust
import com.example.notetify.adapters.FriendRequestAdapter; // Adjust
import com.example.notetify.models.FriendRequest; // Adjust
import com.example.notetify.models.User; // Adjust

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FriendRequestsActivity extends AppCompatActivity implements FriendRequestAdapter.OnRequestListener {

    private static final String TAG = "FriendRequestsActivity";

    private RecyclerView recyclerViewRequests;
    private FriendRequestAdapter adapter;
    private List<FriendRequest> requestList;
    private ProgressBar progressBar;
    private TextView textViewNoRequests; // To show when list is empty

    private FirebaseAuth mAuth;
    private FirebaseDatabase database;
    private FirebaseUser currentUser;
    private DatabaseReference requestsRef;
    private ValueEventListener requestsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_friend_requests);

        Toolbar toolbar = findViewById(R.id.toolbarFriendRequests);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Friend Requests");
        }

        mAuth = FirebaseAuth.getInstance();
        database = FirebaseDatabase.getInstance();
        currentUser = mAuth.getCurrentUser();

        if (currentUser == null) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        recyclerViewRequests = findViewById(R.id.recyclerViewFriendRequests);
        progressBar = findViewById(R.id.progressBarFriendRequests);
        textViewNoRequests = findViewById(R.id.textViewNoFriendRequests);

        recyclerViewRequests.setLayoutManager(new LinearLayoutManager(this));
        requestList = new ArrayList<>();
        adapter = new FriendRequestAdapter(requestList, this);
        recyclerViewRequests.setAdapter(adapter);

        requestsRef = database.getReference("friend_requests").child(currentUser.getUid());
    }

    @Override
    protected void onResume() {
        super.onResume();
        attachRequestsListener();
    }

    @Override
    protected void onPause() {
        super.onPause();
        detachRequestsListener();
    }

    private void attachRequestsListener() {
        progressBar.setVisibility(View.VISIBLE);
        textViewNoRequests.setVisibility(View.GONE);

        if (requestsListener == null) {
            requestsListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                    requestList.clear(); // Clear before processing new snapshot
                    if (!dataSnapshot.exists() || dataSnapshot.getChildrenCount() == 0) {
                        textViewNoRequests.setVisibility(View.VISIBLE);
                    } else {
                        textViewNoRequests.setVisibility(View.GONE);
                        for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                            String senderUid = snapshot.getKey();
                            String status = snapshot.child("status").getValue(String.class);
                            Long timestamp = snapshot.child("timestamp").getValue(Long.class);

                            if ("pending".equals(status) && senderUid != null && timestamp != null) {
                                fetchSenderNameAndAddRequest(senderUid, status, timestamp);
                            }
                        }
                        // Check if after fetching names, the list of *pending* requests is still empty
                        // This is tricky because fetchSenderNameAndAddRequest is async
                        // A better approach might be to update the adapter within fetchSenderNameAndAddRequest
                        // and handle the "no requests" visibility there or after all fetches.
                        // For now, the adapter update is inside fetchSenderNameAndAddRequest.
                    }
                    if (requestList.isEmpty()) { // If list is empty after initial processing (before async name fetches might complete)
                        adapter.updateRequests(new ArrayList<>(requestList)); // Ensure adapter is cleared
                        textViewNoRequests.setVisibility(View.VISIBLE);
                    }
                    progressBar.setVisibility(View.GONE);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    Log.e(TAG, "Failed to load friend requests.", databaseError.toException());
                    progressBar.setVisibility(View.GONE);
                    textViewNoRequests.setVisibility(View.VISIBLE);
                    Toast.makeText(FriendRequestsActivity.this, "Could not load requests.", Toast.LENGTH_SHORT).show();
                }
            };
            requestsRef.addValueEventListener(requestsListener);
        } else {
            if (requestList.isEmpty()) {
                progressBar.setVisibility(View.VISIBLE);
                textViewNoRequests.setVisibility(View.GONE);
            } else {
                progressBar.setVisibility(View.GONE);
            }
        }
    }

    private void fetchSenderNameAndAddRequest(String senderUid, String status, long timestamp) {
        DatabaseReference senderRef = database.getReference("users").child(senderUid);
        senderRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String senderName = senderUid;
                if (snapshot.exists()) {
                    User senderUser = snapshot.getValue(User.class);
                    if (senderUser != null) {
                        if (senderUser.getDisplayName() != null && !senderUser.getDisplayName().isEmpty()) {
                            senderName = senderUser.getDisplayName();
                        } else if (senderUser.getEmail() != null) {
                            senderName = senderUser.getEmail();
                        }
                    }
                }
                FriendRequest request = new FriendRequest(senderUid, senderName, status, timestamp);

                // Prevent duplicates if data changes rapidly or listener re-fires
                boolean alreadyExists = false;
                for (int i = 0; i < requestList.size(); i++) {
                    if (requestList.get(i).senderUid.equals(senderUid)) {
                        requestList.set(i, request); // Update if already exists (e.g., name loaded)
                        alreadyExists = true;
                        break;
                    }
                }
                if (!alreadyExists) {
                    requestList.add(request);
                }

                adapter.updateRequests(new ArrayList<>(requestList));
                if (requestList.isEmpty()) {
                    textViewNoRequests.setVisibility(View.VISIBLE);
                } else {
                    textViewNoRequests.setVisibility(View.GONE);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Could not fetch sender name for " + senderUid, error.toException());
                FriendRequest request = new FriendRequest(senderUid, senderUid, status, timestamp); // Use UID as name
                boolean alreadyExists = false;
                for (int i = 0; i < requestList.size(); i++) {
                    if (requestList.get(i).senderUid.equals(senderUid)) {
                        // Potentially update if you want to overwrite with UID if name fetch failed
                        // requestList.set(i, request);
                        alreadyExists = true;
                        break;
                    }
                }
                if (!alreadyExists) {
                    requestList.add(request);
                }
                adapter.updateRequests(new ArrayList<>(requestList));
                if (requestList.isEmpty()) {
                    textViewNoRequests.setVisibility(View.VISIBLE);
                } else {
                    textViewNoRequests.setVisibility(View.GONE);
                }
            }
        });
    }


    private void detachRequestsListener() {
        if (requestsRef != null && requestsListener != null) {
            requestsRef.removeEventListener(requestsListener);
            requestsListener = null;
        }
    }

    // ------------- MODIFICATION START -------------
// In FriendRequestsActivity.java
    @Override
    public void onAcceptRequest(FriendRequest request) {
        if (currentUser == null || request == null || request.senderUid == null) {
            Toast.makeText(this, "Error: Crucial data missing for accept.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "onAcceptRequest: currentUser, request, or senderUid is null.");
            return;
        }
        progressBar.setVisibility(View.VISIBLE);

        String currentUserId_REQUEST_RECEIVER = currentUser.getUid(); // User B
        String senderUid_REQUEST_SENDER = request.senderUid;       // User A

        Log.i(TAG, "ACCEPTING REQUEST: Receiver (current user): " + currentUserId_REQUEST_RECEIVER + ", Sender: " + senderUid_REQUEST_SENDER);

        DatabaseReference rootRef = database.getReference();
        Map<String, Object> childUpdates = new HashMap<>();

        String pathForReceiverFriends = "/users/" + currentUserId_REQUEST_RECEIVER + "/friends/" + senderUid_REQUEST_SENDER;
        String pathForSenderFriends = "/users/" + senderUid_REQUEST_SENDER + "/friends/" + currentUserId_REQUEST_RECEIVER;
        String pathToClearRequest = "/friend_requests/" + currentUserId_REQUEST_RECEIVER + "/" + senderUid_REQUEST_SENDER;

        childUpdates.put(pathForReceiverFriends, true);
        childUpdates.put(pathForSenderFriends, true);
        childUpdates.put(pathToClearRequest, null);

        Log.d(TAG, "Attempting Firebase multi-path update with the following data: " + childUpdates.toString());

        rootRef.updateChildren(childUpdates, (databaseError, databaseReference) -> {
            progressBar.setVisibility(View.GONE);
            if (databaseError == null) {
                // THIS IS THE SUCCESS CASE
                Log.i(TAG, "SUCCESS: Friend request accepted. Firebase multi-path update was successful.");
                Toast.makeText(FriendRequestsActivity.this, "Friend request from " + request.senderName + " accepted!", Toast.LENGTH_SHORT).show();
                // The ValueEventListener on requestsRef in this activity should then update the UI.
            } else {
                // THIS IS THE FAILURE CASE
                Log.e(TAG, "FAILURE: Failed to accept friend request during multi-path update.");
                Log.e(TAG, "Error Message: " + databaseError.getMessage());
                Log.e(TAG, "Error Details: " + databaseError.getDetails());
                Log.e(TAG, "Error Exception: ", databaseError.toException()); // Log the full exception
                Toast.makeText(FriendRequestsActivity.this, "Failed to accept request: " + databaseError.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }
    // ------------- MODIFICATION END -------------


    @Override
    public void onDeclineRequest(FriendRequest request) {
        if (currentUser == null || request == null || request.senderUid == null) {
            Toast.makeText(this, "Error processing request.", Toast.LENGTH_SHORT).show();
            return;
        }
        progressBar.setVisibility(View.VISIBLE);

        String currentUserId = currentUser.getUid();
        String senderUid = request.senderUid;

        DatabaseReference requestNodeRef = database.getReference("friend_requests")
                .child(currentUserId)
                .child(senderUid);

        requestNodeRef.removeValue((databaseError, databaseReference) -> {
            progressBar.setVisibility(View.GONE);
            if (databaseError == null) {
                Toast.makeText(FriendRequestsActivity.this, "Friend request from " + request.senderName + " declined.", Toast.LENGTH_SHORT).show();
                // ValueEventListener should update the list
            } else {
                Toast.makeText(FriendRequestsActivity.this, "Failed to decline request. " + databaseError.getMessage(), Toast.LENGTH_LONG).show();
                Log.e(TAG, "Failed to decline friend request", databaseError.toException());
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
