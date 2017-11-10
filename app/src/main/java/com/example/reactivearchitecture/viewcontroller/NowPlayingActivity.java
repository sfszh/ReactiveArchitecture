/*
Copyright 2017 LEO LLC

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.example.reactivearchitecture.viewcontroller;

import android.arch.lifecycle.ViewModelProvider;
import android.arch.lifecycle.ViewModelProviders;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.Toast;

import com.example.reactivearchitecture.R;
import com.example.reactivearchitecture.adapter.NowPlayingListAdapter;
import com.example.reactivearchitecture.adapter.ScrollEventCalculator;
import com.example.reactivearchitecture.databinding.ActivityNowPlayingBinding;
import com.example.reactivearchitecture.model.AdapterCommandType;

import com.example.reactivearchitecture.model.MovieViewInfo;
import com.example.reactivearchitecture.model.UiModel;
import com.example.reactivearchitecture.model.event.ScrollEvent;
import com.example.reactivearchitecture.view.DividerItemDecoration;
import com.example.reactivearchitecture.viewmodel.NowPlayingViewModel;
import com.jakewharton.rxbinding2.support.v7.widget.RxRecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import timber.log.Timber;

/**
 * This is the only activity for the application.
 */
public class NowPlayingActivity extends BaseActivity {
    private static final String LAST_SCROLL_POSITION = "LAST_SCROLL_POSITION";
    private static final String LAST_UIMODEL = "LAST_UIMODEL";

    private NowPlayingListAdapter nowPlayingListAdapter;
    private NowPlayingViewModel nowPlayingViewModel;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private ActivityNowPlayingBinding nowPlayingBinding;
    private Disposable scrollDisposable;
    private Parcelable savedRecyclerLayoutState;
    private UiModel latestUiModel;

    @Inject
    ViewModelProvider.Factory viewModelFactory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);

        //Create / Retrieve ViewModel
        nowPlayingViewModel = ViewModelProviders.of(this, viewModelFactory).get(NowPlayingViewModel.class);

        //Create & Set Binding
        nowPlayingBinding = DataBindingUtil.setContentView(this, R.layout.activity_now_playing);
        nowPlayingBinding.setViewModel(nowPlayingViewModel);

        // Sets the Toolbar to act as the ActionBar for this Activity window.
        // Make sure the toolbar exists in the activity and is not null
        setSupportActionBar(nowPlayingBinding.toolbar);

        //restore
        UiModel savedUiModel = null;
        if (savedInstanceState != null) {
            savedRecyclerLayoutState = savedInstanceState.getParcelable(LAST_SCROLL_POSITION);
            savedUiModel = savedInstanceState.getParcelable(LAST_UIMODEL);
        }

        //init viewModel
        nowPlayingViewModel.init(savedUiModel);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bind();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(LAST_SCROLL_POSITION, nowPlayingBinding.recyclerView.getLayoutManager().onSaveInstanceState());
        outState.putParcelable(LAST_UIMODEL, latestUiModel);
    }

    @Override
    public void onStop() {
        super.onStop();

        //un-subscribe
        compositeDisposable.clear();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        //un-bind (Android Databinding)
        nowPlayingBinding.unbind();
    }

    /**
     * Create the adapter for {@link RecyclerView}.
     * @param adapterList - List that backs the adapter.
     */
    private void createAdapter(List<MovieViewInfo> adapterList) {
        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(this);

        nowPlayingBinding.recyclerView.setLayoutManager(linearLayoutManager);
        nowPlayingBinding.recyclerView.addItemDecoration(new DividerItemDecoration(
                this,
                DividerItemDecoration.VERTICAL_LIST,
                getResources().getColor(android.R.color.black, null)));
        nowPlayingListAdapter = new NowPlayingListAdapter(adapterList);
        nowPlayingBinding.recyclerView.setAdapter(nowPlayingListAdapter);
    }

    /**
     * Bind to all data in {@link NowPlayingViewModel}.
     */
    private void bind() {
        //
        //Bind to UiModel
        //
        compositeDisposable.add(nowPlayingViewModel.getUiModels()
                .subscribe(this::processUiModel, throwable -> {
                    throw new UnsupportedOperationException("Errors from Model Unsupported: "
                            + throwable.getLocalizedMessage());
                })
        );
    }

    /**
     * Bind to scroll events.
     */
    @SuppressWarnings("checkstyle:magicnumber")
    private void bindToScrollEvent() {
        scrollDisposable = RxRecyclerView.scrollEvents(nowPlayingBinding.recyclerView)
                //Bind to RxBindings.
                .flatMap(recyclerViewScrollEvent -> {
                    ScrollEventCalculator scrollEventCalculator =
                            new ScrollEventCalculator(recyclerViewScrollEvent);

                    //Only handle 'is at end' of list scroll events
                    if (scrollEventCalculator.isAtScrollEnd()) {
                        ScrollEvent scrollEvent = new ScrollEvent();
                        scrollEvent.setPageNumber(latestUiModel.getPageNumber() + 1);
                        return Observable.just(scrollEvent);
                    } else {
                        return Observable.empty();
                    }
                })
                //Filter any multiple events before 250MS
                .debounce(scrollEvent -> Observable.<ScrollEvent>empty().delay(250, TimeUnit.MILLISECONDS))
                //Send ScrollEvent to ViewModel
                .subscribe(scrollEvent -> nowPlayingViewModel.processUiEvent(scrollEvent), throwable -> {
                    throw new UnsupportedOperationException("Errors in scroll event unsupported. Crash app."
                            + throwable.getLocalizedMessage());
                });

        compositeDisposable.add(scrollDisposable);
    }

    /**
     * Unbind from scroll events.
     */
    private void unbindFromScrollEvent() {
        if (scrollDisposable != null) {
            scrollDisposable.dispose();
            compositeDisposable.delete(scrollDisposable);
        }
    }

    /**
     * Bind to {@link UiModel}.
     * @param uiModel - the {@link UiModel} from {@link NowPlayingViewModel} that backs the UI.
     */
    private void processUiModel(UiModel uiModel) {
        /*
        Note - Keep the logic here as SIMPLE as possible.
         */
        Timber.i("Thread name: %s. Update UI based on UiModel.", Thread.currentThread().getName());
        this.latestUiModel = uiModel;

        //
        //Update progressBar
        //
        nowPlayingBinding.progressBar.setVisibility(
                uiModel.isFirstTimeLoad() ? View.VISIBLE : View.GONE);

        //
        //Scroll Listener
        //
        if (uiModel.isEnableScrollListener()) {
            bindToScrollEvent();
        } else {
            unbindFromScrollEvent();
        }

        //
        //Update adapter
        //
        if (nowPlayingListAdapter == null) {
            //Note, get returns a shallow-copy
            ArrayList<MovieViewInfo> adapterData = (ArrayList<MovieViewInfo>) uiModel.getCurrentList();

            //Process last adapter command
            if (uiModel.getAdapterCommandType() == AdapterCommandType.ADD_DATA_ONLY
                    || uiModel.getAdapterCommandType() == AdapterCommandType.ADD_DATA_REMOVE_IN_PROGRESS) {
                adapterData.addAll(uiModel.getResultList());
            } else if (uiModel.getAdapterCommandType() == AdapterCommandType.SHOW_IN_PROGRESS) {
                adapterData.add(null);
            }

            //create adapter
            createAdapter(adapterData);

            //Restore adapter state
            if (savedRecyclerLayoutState != null) {
                nowPlayingBinding.recyclerView.getLayoutManager().onRestoreInstanceState(savedRecyclerLayoutState);
            }

        } else {
            switch (uiModel.getAdapterCommandType()) {
                case AdapterCommandType.ADD_DATA_REMOVE_IN_PROGRESS:
                    //Remove Null Spinner
                    if (nowPlayingListAdapter.getItemCount() > 0) {
                        nowPlayingListAdapter.remove(
                                nowPlayingListAdapter.getItem(nowPlayingListAdapter.getItemCount() - 1));
                    }

                    //Add Data
                    nowPlayingListAdapter.addList(uiModel.getResultList());
                    break;
                case AdapterCommandType.ADD_DATA_ONLY:
                    //Add Data
                    nowPlayingListAdapter.addList(uiModel.getResultList());
                    break;
                case AdapterCommandType.SHOW_IN_PROGRESS:
                    //Add null to adapter. Null shows spinner in Adapter logic.
                    nowPlayingListAdapter.add(null);
                    nowPlayingBinding.recyclerView.scrollToPosition(nowPlayingListAdapter.getItemCount() - 1);
                    break;
                default:
                    //No-Op
            }
        }

        //
        //Error Messages
        //
        if (uiModel.getFailureMsg() != null && !uiModel.getFailureMsg().isEmpty()) {
            Toast.makeText(NowPlayingActivity.this, R.string.error_msg, Toast.LENGTH_LONG).show();
        }
    }
}