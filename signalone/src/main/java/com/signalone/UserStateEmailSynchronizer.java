package com.signalone;

import org.json.JSONException;
import org.json.JSONObject;

class UserStateEmailSynchronizer extends UserStateSynchronizer {

    @Override
    protected UserState newUserState(String inPersistKey, boolean load) {
        return new UserStateEmail(inPersistKey, load);
    }

    // Email subscription not readable from SDK
    @Override
    boolean getSubscribed() {
        return false;
    }

    // Email tags not readable from SDK
    @Override
    GetTagsResult getTags(boolean fromServer) {
        return null;
    }

    // Email subscription not settable from SDK
    @Override
    void setSubscription(boolean enable) {}

    // Email does not have a user preference on the SDK
    @Override
    public boolean getUserSubscribePreference() {
        return false;
    }

    // Email subscription not readable from SDK
    @Override
    public void setPermission(boolean enable) {}

    @Override
    void updateState(JSONObject state) {}

    void refresh() {
        scheduleSyncToServer();
    }

    @Override
    protected void scheduleSyncToServer() {
        // Don't make a POST / PUT network call if we never set an email.
        boolean neverEmail = getId() == null && getRegistrationId() == null;
        if (neverEmail || SignalOne.getUserId() == null)
            return;

        getNetworkHandlerThread(NetworkHandlerThread.NETWORK_HANDLER_USERSTATE).runNewJobDelayed();
    }

    void setEmail(String email, String emailAuthHash) {
        JSONObject syncValues = getUserStateForModification().syncValues;

        boolean noChange = email.equals(syncValues.optString("identifier")) &&
                syncValues.optString("email_auth_hash").equals(emailAuthHash == null ? "" : emailAuthHash);
        if (noChange) {
            SignalOne.fireEmailUpdateSuccess();
            return;
        }

        String existingEmail = syncValues.optString("identifier", null);

        if (existingEmail == null)
            setNewSession();

        try {
            JSONObject emailJSON = new JSONObject();
            emailJSON.put("identifier", email);

            if (emailAuthHash != null)
                emailJSON.put("email_auth_hash", emailAuthHash);

            if (emailAuthHash == null) {
                if (existingEmail != null && !existingEmail.equals(email)) {
                    SignalOne.saveEmailId("");
                    resetCurrentState();
                    setNewSession();
                }
            }

            generateJsonDiff(syncValues, emailJSON, syncValues, null);
            scheduleSyncToServer();
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected String getId() {
        return SignalOne.getEmailId();
    }

    @Override
    void updateIdDependents(String id) {
        SignalOne.updateEmailIdDependents(id);
    }

    @Override
    protected void addOnSessionOrCreateExtras(JSONObject jsonBody) {
        try {
            jsonBody.put("device_type", 11);
            jsonBody.putOpt("device_player_id", SignalOne.getUserId());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @Override
    void logoutEmail() {
        SignalOne.saveEmailId("");

        resetCurrentState();
        getToSyncUserState().syncValues.remove("identifier");
        toSyncUserState.syncValues.remove("email_auth_hash");
        toSyncUserState.syncValues.remove("device_player_id");
        toSyncUserState.persistState();

        SignalOne.getPermissionSubscriptionState().emailSubscriptionStatus.clearEmailAndId();
    }

    @Override
    protected void fireEventsForUpdateFailure(JSONObject jsonFields) {
        if (jsonFields.has("identifier"))
            SignalOne.fireEmailUpdateFailure();
    }

    @Override
    protected void onSuccessfulSync(JSONObject jsonFields) {
        if (jsonFields.has("identifier"))
            SignalOne.fireEmailUpdateSuccess();
    }
}
