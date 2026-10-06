package com.knowledgeflow.users.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.UserStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** ADR-006 User domain: every credential/status change increments token_version, never decreases it. */
class UserCredentialDomainTest {

    private static User user() {
        return new User("pessoa@taxia.local", "Pessoa", "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyzABCDE");
    }

    @Test
    void newUserStartsAtVersionZeroWithoutForcedChange() {
        User user = user();
        assertThat(user.getTokenVersion()).isZero();
        assertThat(user.isMustChangePassword()).isFalse();
    }

    @Test
    void changePasswordIncrementsAndClearsForcedChange() {
        User user = user();
        user.resetPassword("temp-hash");
        assertThat(user.isMustChangePassword()).isTrue();
        assertThat(user.getTokenVersion()).isEqualTo(1);
        user.changePassword("final-hash");
        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(user.getPasswordHash()).isEqualTo("final-hash");
        assertThat(user.getTokenVersion()).isEqualTo(2);
    }

    @Test
    void revokeDisableReactivateAndDeleteAllIncrement() {
        User user = user();
        user.revokeSessions();
        user.disable();
        assertThat(user.getStatus()).isEqualTo(UserStatus.DISABLED);
        int disabledAt = user.getTokenVersion();
        user.reactivate();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getTokenVersion()).isGreaterThan(disabledAt);
        user.softDelete();
        assertThat(user.isDeleted()).isTrue();
        assertThat(user.getTokenVersion()).isEqualTo(4);
    }

    @Test
    void redundantStatusChangesAreRefused() {
        User user = user();
        assertThatThrownBy(user::reactivate).isInstanceOf(IllegalStateException.class);
        user.disable();
        assertThatThrownBy(user::disable).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankHashRefused() {
        assertThatThrownBy(() -> user().changePassword(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> user().resetPassword(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void counterNeverWrapsAround() throws Exception {
        User user = user();
        Field field = User.class.getDeclaredField("tokenVersion");
        field.setAccessible(true);
        field.setInt(user, Integer.MAX_VALUE);
        assertThatThrownBy(user::revokeSessions).isInstanceOf(IllegalStateException.class);
        assertThat(user.getTokenVersion()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void noRawPasswordOrVersionSetter() {
        assertThat(Arrays.stream(User.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName))
                .noneMatch(n -> n.equals("setPasswordHash") || n.equals("setTokenVersion")
                        || n.equals("setMustChangePassword") || n.equals("setStatus"));
    }
}
