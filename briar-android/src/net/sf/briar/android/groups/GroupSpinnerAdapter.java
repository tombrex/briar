package net.sf.briar.android.groups;

import java.util.ArrayList;

import net.sf.briar.R;
import net.sf.briar.api.messaging.Group;
import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

class GroupSpinnerAdapter extends ArrayAdapter<Group>
implements SpinnerAdapter {

	GroupSpinnerAdapter(Context context) {
		super(context, android.R.layout.simple_spinner_item,
				new ArrayList<Group>());
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		TextView name = new TextView(getContext());
		name.setTextSize(18);
		name.setMaxLines(1);
		Resources res = getContext().getResources();
		int pad = res.getInteger(R.integer.spinner_padding);
		name.setPadding(pad, pad, pad, pad);
		name.setText(getItem(position).getName());
		return name;
	}

	@Override
	public View getDropDownView(int position, View convertView,
			ViewGroup parent) {
		return getView(position, convertView, parent);
	}
}
