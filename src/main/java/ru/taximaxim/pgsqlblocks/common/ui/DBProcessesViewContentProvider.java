package ru.taximaxim.pgsqlblocks.common.ui;

import org.eclipse.jface.viewers.ITreeContentProvider;

public class DBProcessesViewContentProvider implements ITreeContentProvider {

    @Override
    public Object[] getElements(Object inputElement) {
        return new Object[0];
    }

    @Override
    public Object[] getChildren(Object parentElement) {
        return new Object[0];
    }

    @Override
    public Object getParent(Object element) {
        return null;
    }

    @Override
    public boolean hasChildren(Object element) {
        return false;
    }
}
