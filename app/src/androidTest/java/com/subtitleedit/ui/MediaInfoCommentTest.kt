package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.ui.components.MediaInfoComment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaInfoCommentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun threeVisibleLinesDoNotShowTheExpandButton() {
        showComment("One\nTwo\nThree")
        compose.onNodeWithText(expandLabel()).assertDoesNotExist()
    }

    @Test fun aWrappedCommentOpensInFullAndCanBeDismissed() {
        val comment = "One two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen " +
            "seventeen eighteen nineteen twenty twenty-one twenty-two twenty-three twenty-four twenty-five twenty-six. End."
        showComment(comment)
        compose.onNodeWithText(expandLabel()).assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.media_convert_info_comment)).assertIsDisplayed()
        compose.onNode(hasText(comment) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.player_info_done)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.media_convert_info_comment)).assertDoesNotExist()
        compose.onNodeWithText(expandLabel()).assertIsDisplayed()
    }

    private fun showComment(comment: String) = compose.setContent {
        MaterialTheme {
            MediaInfoComment(comment, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(220.dp))
        }
    }

    private fun expandLabel() = compose.activity.getString(R.string.media_details_open_comment)
}
