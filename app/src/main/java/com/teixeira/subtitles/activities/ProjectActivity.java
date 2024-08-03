/*
 * This file is part of SubTypo.
 *
 * SubTypo is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * SubTypo is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with SubTypo.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.teixeira.subtitles.activities;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.os.BundleCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.blankj.utilcode.util.FileIOUtils;
import com.blankj.utilcode.util.SizeUtils;
import com.blankj.utilcode.util.ThreadUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.teixeira.subtitles.R;
import com.teixeira.subtitles.adapters.SubtitleListAdapter;
import com.teixeira.subtitles.callbacks.GetSubtitleListAdapterCallback;
import com.teixeira.subtitles.databinding.ActivityProjectBinding;
import com.teixeira.subtitles.fragments.sheets.SubtitleEditorSheetFragment;
import com.teixeira.subtitles.managers.UndoManager;
import com.teixeira.subtitles.models.Project;
import com.teixeira.subtitles.models.Subtitle;
import com.teixeira.subtitles.models.VideoInfo;
import com.teixeira.subtitles.preferences.Preferences;
import com.teixeira.subtitles.project.ProjectManager;
import com.teixeira.subtitles.tasks.TaskExecutor;
import com.teixeira.subtitles.ui.ExportWindow;
import com.teixeira.subtitles.utils.DialogUtils;
import com.teixeira.subtitles.utils.FileUtil;
import com.teixeira.subtitles.utils.ToastUtils;
import com.teixeira.subtitles.utils.VideoUtils;
import java.util.List;

public class ProjectActivity extends BaseActivity
    implements GetSubtitleListAdapterCallback, SubtitleListAdapter.SubtitleListener {

  public static final String KEY_PROJECT = "project";
  public static final String KEY_VIDEO_INFO = "video_info";
  public static final String KEY_UNDO_MANAGER = "undo_manager";

  private static final Handler mainHandler = ThreadUtils.getMainHandler();

  private ActivityProjectBinding binding;
  private ProjectManager projectManager;
  private UndoManager undoManager;
  private Project project;
  private SubtitleListAdapter subtitleListAdapter;
  private VideoInfo videoInfo;

  private Runnable onEverySecond;
  private Runnable saveProjectCallback;

  private ActivityResultLauncher<String[]> subtitleDocumentPicker;
  private ExportWindow exportWindow;

  @Override
  protected View bindView() {
    binding = ActivityProjectBinding.inflate(getLayoutInflater());
    return binding.getRoot();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setSupportActionBar(binding.toolbar);
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    binding.toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
    if (savedInstanceState != null) {
      videoInfo = BundleCompat.getParcelable(savedInstanceState, KEY_VIDEO_INFO, VideoInfo.class);
      undoManager =
          BundleCompat.getParcelable(savedInstanceState, KEY_UNDO_MANAGER, UndoManager.class);
      undoManager.setEnabled(Preferences.isDevelopmentUndoAndRedoEnabled());
    } else {
      videoInfo = new VideoInfo(0);
      undoManager = new UndoManager(15);
    }
    subtitleListAdapter = new SubtitleListAdapter(this);
    projectManager = ProjectManager.getInstance();

    if (!Preferences.isDevelopmentUndoAndRedoEnabled()) {
      binding.videoControllerContent.redo.setVisibility(View.GONE);
      binding.videoControllerContent.undo.setVisibility(View.GONE);
    }

    Bundle extras = getIntent().getExtras();
    project =
        projectManager.setupProject(BundleCompat.getParcelable(extras, KEY_PROJECT, Project.class));
    getSupportActionBar().setTitle(project.getName());
    getSupportActionBar().setSubtitle(project.getProjectId());

    subtitleDocumentPicker =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onPickSubtitleFile);
    exportWindow = new ExportWindow(this);
    onEverySecond = this::onEverySecond;
    saveProjectCallback = this::saveProjectAsync;
  }

  @Override
  protected void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    configureProject(savedInstanceState);
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);

    outState.putParcelable(KEY_VIDEO_INFO, videoInfo);
    outState.putParcelable(KEY_UNDO_MANAGER, undoManager);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.activity_project_menu, menu);
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {

    if (item.getItemId() == R.id.menu_export) {
      if (subtitleListAdapter.getSubtitles().isEmpty()) {
        ToastUtils.showShort(R.string.error_no_subtitles_to_export);
        return false;
      }
      stopVideo();
      exportWindow.showAsDropDown(binding.getRoot(), Gravity.CENTER, 0, 0);
    } else if (item.getItemId() == R.id.menu_import) {
      subtitleDocumentPicker.launch(new String[] {"*/*"});
    }

    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();

    mainHandler.removeCallbacks(onEverySecond);
    onEverySecond = null;

    mainHandler.removeCallbacks(saveProjectCallback);
    saveProjectCallback = null;

    projectManager.destroy();
    exportWindow.destroy();
    binding = null;
  }

  @Override
  protected void onPause() {
    stopVideo();
    updateVideoInfo();
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    loadVideoInfo();
  }

  @Override
  public SubtitleListAdapter getSubtitleListAdapter() {
    return this.subtitleListAdapter;
  }

  @Override
  public void onUpdateSubtitles(List<Subtitle> subtitles, boolean save, boolean updateundoManager) {
    if (updateundoManager) {
      undoManager.pushStack(subtitles);
    }

    if (save) {
      mainHandler.removeCallbacks(saveProjectCallback);
      mainHandler.postDelayed(saveProjectCallback, 10L);
    }
    binding.timeLine.setSubtitles(subtitles);
    binding.noSubtitles.setVisibility(subtitles.isEmpty() ? View.VISIBLE : View.GONE);
    callEverySecond(50L);
  }

  @Override
  public void onSubtitleClickListener(View view, int index, Subtitle subtitle) {
    stopVideo();
    SubtitleEditorSheetFragment.newInstance(
            binding.videoContent.videoView.getCurrentPosition(), index, subtitle)
        .show(getSupportFragmentManager(), null);
  }

  @Override
  public boolean onSubtitleLongClickListener(View view, int index, Subtitle subtitle) {

    return true;
  }

  @Override
  public void scrollToPosition(int position) {
    binding.subtitles.scrollToPosition(position);
  }

  private void configureProject(Bundle savedInstanceState) {
    MaterialAlertDialogBuilder builder =
        DialogUtils.createProgressDialog(this, getString(R.string.proj_loading), false);
    AlertDialog dialog = builder.show();

    TaskExecutor.executeAsyncProvideError(
        () -> {
          setListeners();

          return project.getSubtitles();
        },
        (result, throwable) -> {
          dialog.dismiss();
          if (throwable != null) {
            DialogUtils.createSimpleDialog(
                    this, getString(R.string.error_loading_project), throwable.toString())
                .setPositiveButton(R.string.proj_close, (d, w) -> finish())
                .setCancelable(false)
                .show();
            return;
          }
          binding.videoContent.videoView.setVideoPath(project.getVideoPath());
          subtitleListAdapter.setSubtitles(result, false, true);
          exportWindow.setSubtitleListAdapter(subtitleListAdapter);
          binding.subtitles.setLayoutManager(new LinearLayoutManager(this));
          binding.subtitles.setAdapter(subtitleListAdapter);

          ItemTouchHelper touchHelper =
              new ItemTouchHelper(new SubtitleListAdapter.SubtitleTouchHelper(subtitleListAdapter));
          touchHelper.attachToRecyclerView(binding.subtitles);

          subtitleListAdapter.setTouchHelper(touchHelper);
        });
  }

  private void setListeners() {
    binding.videoContent.videoView.setOnPreparedListener(this::onVideoPrepared);
    binding.videoContent.videoView.setOnCompletionListener(this::onVideoCompletion);

    /*binding.videoControllerContent.timeLine.setOnMoveHandlerListener(
    new TimeLineView.OnMoveHandlerListener() {

      private boolean wasPlaying;

      @Override
      public void onMoveHandler(long position) {
        binding.videoContent.videoView.seekTo((int) position);
        callEverySecond(20L);
      }

      @Override
      public void onStartTouch() {
        wasPlaying = binding.videoContent.videoView.isPlaying();
        if (wasPlaying) stopVideo();
      }

      @Override
      public void onStopTouch() {
        if (wasPlaying) playVideo();
      }
    });*/

    binding.videoControllerContent.seekBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {

          private boolean wasPlaying;

          @Override
          public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) {
              binding.videoContent.videoView.seekTo(progress);
              callEverySecond(5L);
            }
          }

          @Override
          public void onStartTrackingTouch(SeekBar seekBar) {
            wasPlaying = binding.videoContent.videoView.isPlaying();
            if (wasPlaying) stopVideo();
          }

          @Override
          public void onStopTrackingTouch(SeekBar seekBar) {
            if (wasPlaying) playVideo();
          }
        });

    binding.videoControllerContent.play.setOnClickListener(
        v -> {
          if (binding.videoContent.videoView.isPlaying()) {
            stopVideo();
          } else playVideo();
        });

    binding.videoControllerContent.undo.setOnClickListener(
        v -> {
          List<Subtitle> subtitles = undoManager.undo();
          if (subtitles != null) {
            subtitleListAdapter.setSubtitles(subtitles, true, false);
          }
        });
    binding.videoControllerContent.redo.setOnClickListener(
        v -> {
          List<Subtitle> subtitles = undoManager.redo();
          if (subtitles != null) {
            subtitleListAdapter.setSubtitles(subtitles, true, false);
          }
        });
    binding.videoControllerContent.skipBackward.setOnClickListener(v -> back5sec());
    binding.videoControllerContent.skipFoward.setOnClickListener(v -> skip5sec());
    binding.videoControllerContent.addSubtitle.setOnClickListener(
        v -> {
          stopVideo();
          SubtitleEditorSheetFragment.newInstance(
                  binding.videoContent.videoView.getCurrentPosition())
              .show(getSupportFragmentManager(), null);
        });
  }

  private void updateVideoInfo() {
    videoInfo.setCurrentVideoPosition(binding.videoContent.videoView.getCurrentPosition());
  }

  private void loadVideoInfo() {
    binding.videoContent.videoView.seekTo(videoInfo.getCurrentVideoPosition());
  }

  private void back5sec() {
    int seek = binding.videoContent.videoView.getCurrentPosition() - 5000;
    if (binding.videoContent.videoView.getCurrentPosition() <= 5000) {
      seek = 0;
    }

    binding.videoContent.videoView.seekTo(seek);
    if (!binding.videoContent.videoView.isPlaying()) {
      callEverySecond();
    }
  }

  private void skip5sec() {
    int seek = binding.videoContent.videoView.getCurrentPosition() + 5000;
    if (seek > binding.videoContent.videoView.getDuration()) {
      seek = binding.videoContent.videoView.getDuration();
    }

    binding.videoContent.videoView.seekTo(seek);
    if (!binding.videoContent.videoView.isPlaying()) {
      callEverySecond();
    }
  }

  private void playVideo() {
    binding.videoControllerContent.play.setImageResource(R.drawable.ic_pause);
    binding.videoContent.videoView.start();
    mainHandler.post(onEverySecond);
  }

  private void stopVideo() {
    binding.videoControllerContent.play.setImageResource(R.drawable.ic_play);
    binding.videoContent.videoView.pause();
  }

  private void onVideoPrepared(MediaPlayer player) {
    binding.videoControllerContent.videoDuration.setText(
        VideoUtils.getTime(binding.videoContent.videoView.getDuration()));
    binding.timeLine.setVideoDuration(binding.videoContent.videoView.getDuration());
    binding.videoControllerContent.seekBar.setMax(binding.videoContent.videoView.getDuration());
    mainHandler.post(onEverySecond);

    int width = player.getVideoWidth();
    int height = player.getVideoHeight();

    if (width > height) {
      binding.videoContent.videoView.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
    } else if (height > width) {
      binding.videoContent.getRoot().getLayoutParams().height = SizeUtils.dp2px(350f);
    }
    loadVideoInfo();
  }

  public void onVideoCompletion(MediaPlayer player) {
    binding.videoControllerContent.play.setImageResource(R.drawable.ic_play);
  }

  private void callEverySecond() {
    callEverySecond(1L);
  }

  private void callEverySecond(long delay) {
    if (onEverySecond == null) {
      return;
    }
    mainHandler.removeCallbacks(onEverySecond);
    mainHandler.postDelayed(onEverySecond, delay);
  }

  private void onEverySecond() {
    int currentVideoPosition = binding.videoContent.videoView.getCurrentPosition();
    binding.videoControllerContent.seekBar.setProgress(currentVideoPosition);
    binding.videoControllerContent.currentVideoPosition.setText(
        VideoUtils.getTime(currentVideoPosition));
    binding.timeLine.setCurrentVideoPosition(currentVideoPosition);

    List<Subtitle> subtitles = subtitleListAdapter.getSubtitles();
    boolean subtitleFound = false;
    for (int i = 0; i < subtitles.size(); i++) {
      try {
        Subtitle subtitle = subtitles.get(i);
        long startTime = VideoUtils.getMilliSeconds(subtitle.getStartTime());
        long endTime = VideoUtils.getMilliSeconds(subtitle.getEndTime());

        if (currentVideoPosition >= startTime && currentVideoPosition <= endTime) {
          binding.videoContent.subtitleView.setSubtitle(subtitle);
          binding.videoContent.subtitleView.setVisibility(View.VISIBLE);
          subtitleListAdapter.setScreenSubtitleIndex(i);
          subtitleFound = true;
          break;
        }
      } catch (Exception e) {
        // ignore
      }
    }

    if (!subtitleFound) {
      binding.videoContent.subtitleView.setVisibility(View.GONE);
      subtitleListAdapter.setScreenSubtitleIndex(-1);
    }

    if (binding.videoContent.videoView.isPlaying()) {
      callEverySecond();
    }
  }

  private void onPickSubtitleFile(Uri uri) {
    if (uri == null) return;
    MaterialAlertDialogBuilder builder =
        DialogUtils.createSimpleDialog(
            this,
            getString(R.string.proj_import_subtitles),
            getString(R.string.msg_import_subtitles_warning));

    builder.setNegativeButton(R.string.cancel, null);
    builder.setPositiveButton(
        R.string.ok,
        (d, w) -> {
          MaterialAlertDialogBuilder progressBuilder =
              DialogUtils.createProgressDialog(this, getString(R.string.proj_loading), false);
          AlertDialog dialog = progressBuilder.show();

          TaskExecutor.executeAsyncProvideError(
              () -> project.getSubtitleFormat().toList(FileUtil.readFileContent(uri)),
              (result, throwable) -> {
                dialog.dismiss();
                if (throwable != null) {
                  DialogUtils.createSimpleDialog(
                          this, getString(R.string.error_reading_subtitles), throwable.toString())
                      .setPositiveButton(R.string.ok, null)
                      .show();
                  return;
                }
                subtitleListAdapter.setSubtitles(result, true, true);
              });
        });
    builder.show();
  }

  private void saveProjectAsync() {
    getSupportActionBar().setSubtitle(R.string.proj_saving);
    TaskExecutor.executeAsyncProvideError(
        this::saveProject,
        (r, throwable) -> getSupportActionBar().setSubtitle(project.getProjectId()));
  }

  private Void saveProject() {
    try {
      FileIOUtils.writeFileFromString(
          project.getProjectPath() + "/" + project.getSubtitleFile().getNameWithExtension(),
          project.getSubtitleFormat().toText(subtitleListAdapter.getSubtitles()));
    } catch (Exception e) {
      DialogUtils.createSimpleDialog(this, getString(R.string.error_saving_project), e.toString())
          .setPositiveButton(R.string.ok, null)
          .show();
    }
    return null;
  }
}
