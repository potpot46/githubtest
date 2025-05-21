package com.example.notetify.activities;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.example.notetify.R;
import com.example.notetify.adapters.UserSearchAdapter;
import com.example.notetify.models.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AddFriendActivity extends AppCompatActivity {

    private static final String TAG = "AddFriendActivity";

    private EditText editTextSearchEmail;
    private Button buttonSearchUser;
    private RecyclerView recyclerViewSearchResults;
    private TextView textViewNoResults;
    private UserSearchAdapter adapter;
    private List<User> searchResults;
    private Map<String, String> friendRequestStatuses;


    private FirebaseDatabase database;
    private FirebaseAuth mAuth;
    private FirebaseUser currentUser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.add_friend_activity); // Create this layout

        // Toolbar setup (optional)
        // Toolbar toolbar = findViewById(R.id.toolbarAddFriend);
        // setSupportActionBar(toolbar);
        // if (getSupportActionBar() != null) {
        //     getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        //     getSupportActionBar().setTitle("Add Friend");
        // }


        editTextSearchEmail = findViewById(R.id.editTextSearchEmail);
        buttonSearchUser = findViewById(R.id.buttonSearchUser);
        recyclerViewSearchResults = findViewById(R.id.recyclerViewSearchResults);
        textViewNoResults = findViewById(R.id.textViewNoResults);

        database = FirebaseDatabase.getInstance();
        mAuth = FirebaseAuth.getInstance();
        currentUser = mAuth.getCurrentUser();

        if (currentUser == null) {
            Toast.makeText(this, "You need to be logged in.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        searchResults = new ArrayList<>();
        friendRequestStatuses = new HashMap<>();
        adapter = new UserSearchAdapter(searchResults, friendRequestStatuses, this::sendFriendRequest);
        recyclerViewSearchResults.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewSearchResults.setAdapter(adapter);

        buttonSearchUser.setOnClickListener(v -> performUserSearch());
    }

    private void performUserSearch() {
        String searchQuery = editTextSearchEmail.getText().toString().trim();
        if (TextUtils.isEmpty(searchQuery)) {
            Toast.makeText(this, "Please enter an email to search.", Toast.LENGTH_SHORT).show();
            return;
        }

        searchResults.clear();
        friendRequestStatuses.clear();
        adapter.notifyDataSetChanged();
        textViewNoResults.setVisibility(View.GONE);

        DatabaseReference usersRef = database.getReference("users");
        Query query = usersRef.orderByChild("email").equalTo(searchQuery);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    textViewNoResults.setVisibility(View.VISIBLE);
                    return;
                }
                for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                    User user = snapshot.getValue(User.class);
                    if (user != null && !user.uid.equals(currentUser.getUid())) {
                        searchResults.add(user);
                        checkFriendshipAndRequestStatus(user); // Check status for each found user
                    }
                }
                if (searchResults.isEmpty()){
                    textViewNoResults.setVisibility(View.VISIBLE);
                }
                // Adapter will be updated inside checkFriendshipAndRequestStatus calls
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "User search failed: " + databaseError.getMessage());
                Toast.makeText(AddFriendActivity.this, "Search failed.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void checkFriendshipAndRequestStatus(User targetUser) {
        // 1. Check if already friends
        DatabaseReference friendsRef = database.getReference("users")
                .child(currentUser.getUid()).child("friends").child(targetUser.uid);
        friendsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    friendRequestStatuses.put(targetUser.uid, "friends");
                    adapter.updateUsers(searchResults, friendRequestStatuses);
                } else {
                    // 2. If not friends, check for pending sent request
                    DatabaseReference sentRequestRef = database.getReference("friend_requests")
                            .child(targetUser.uid).child(currentUser.getUid());
                    sentRequestRef.addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot requestSnapshot) {
                            if (requestSnapshot.exists() && "pending".equals(requestSnapshot.child("status").getValue(String.class))) {
                                friendRequestStatuses.put(targetUser.uid, "pending");
                            } else {
                                // 3. If no pending sent request, check for pending received request
                                DatabaseReference receivedRequestRef = database.getReference("friend_requests")
                                        .child(currentUser.getUid()).child(targetUser.uid);
                                receivedRequestRef.addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override
                                    public void onDataChange(@NonNull DataSnapshot receivedSnap) {
                                        if(receivedSnap.exists() && "pending".equals(receivedSnap.child("status").getValue(String.class))){
                                            // User has a pending request from this person, can't send another one.
                                            // Or you could allow "Accept" here, but that's more complex UI for this adapter.
                                            friendRequestStatuses.put(targetUser.uid, "received_pending"); // Special status
                                        } else {
                                            friendRequestStatuses.put(targetUser.uid, null); // Can send request
                                        }
                                        adapter.updateUsers(searchResults, friendRequestStatuses);
                                    }
                                    @Override
                                    public void onCancelled(@NonNull DatabaseError error) {
                                        adapter.updateUsers(searchResults, friendRequestStatuses);
                                    }
                                });
                            }
                            adapter.updateUsers(searchResults, friendRequestStatuses);
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError databaseError) {
                            adapter.updateUsers(searchResults, friendRequestStatuses);
                        }
                    });
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                adapter.updateUsers(searchResults, friendRequestStatuses);
            }
        });
    }


    private void sendFriendRequest(User toUser) {
        if (currentUser == null || toUser == null || currentUser.getUid().equals(toUser.uid)) {
            return;
        }

        DatabaseReference requestRef = database.getReference("friend_requests")
                .child(toUser.uid) // The receiver of the request
                .child(currentUser.getUid()); // The sender

        Map<String, Object> requestData = new HashMap<>();
        requestData.put("status", "pending");
        requestData.put("timestamp", System.currentTimeMillis());
        // Optionally add sender's name/email for display to receiver
        // requestData.put("senderName", currentUser.getDisplayName());

        requestRef.setValue(requestData)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(AddFriendActivity.this, "Friend request sent to " + (toUser.displayName != null ? toUser.displayName : toUser.email), Toast.LENGTH_SHORT).show();
                    friendRequestStatuses.put(toUser.uid, "pending");
                    adapter.updateUsers(searchResults, friendRequestStatuses); // Update button state
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(AddFriendActivity.this, "Failed to send request.", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "sendFriendRequest:failure", e);
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
